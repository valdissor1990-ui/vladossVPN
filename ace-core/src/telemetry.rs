//! Stage C telemetry admission and bounded in-memory buffer.
//! This is not the Digital Twin and performs no prediction or control.

use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;

const MAX_RECORDS: usize = 4096;

static TELEMETRY: OnceCell<Mutex<VecDeque<AdmittedTelemetry>>> = OnceCell::new();

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TelemetryRecord {
    pub schema_version: u16,
    pub event_id: String,
    pub elapsed_realtime_ms: u64,
    pub metric: String,
    pub value: Option<f64>,
    pub unit: Option<String>,
    pub outcome: String,
    pub missing_reason: Option<String>,
    pub restricted_context: bool,
    pub source: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct AdmittedTelemetry {
    pub schema_version: u16,
    pub event_id: String,
    pub elapsed_realtime_ms: u64,
    pub metric: String,
    pub value: Option<f64>,
    pub unit: Option<String>,
    pub outcome: String,
    pub missing_reason: Option<String>,
    pub restricted_context: bool,
    pub source: String,
    pub independence_group: String,
    pub admitted_to_primary: bool,
}

fn independence_group(source: &str) -> (&'static str, bool) {
    match source {
        "android_network_callback" => ("android_connectivity", true),
        "health_monitor" => ("health_monitor", true),
        "traffic_validator" => ("traffic_validator", true),
        "power_manager" => ("power_manager", true),
        "vpn_service" => ("vpn_lifecycle", true),
        _ => ("unclassified", false),
    }
}

fn valid_metric(metric: &str) -> bool {
    matches!(
        metric,
        "rtt_ms"
            | "jitter_ms"
            | "packet_loss_pct"
            | "traffic_validation_http_status"
            | "traffic_validation_duration_ms"
            | "network_transport"
            | "network_validated"
            | "network_metered"
            | "battery_saver"
            | "device_idle"
            | "vpn_state"
    )
}

fn expected_unit(metric: &str) -> Option<&'static str> {
    match metric {
        "rtt_ms" | "jitter_ms" | "traffic_validation_duration_ms" => Some("ms"),
        "packet_loss_pct" => Some("percent"),
        "traffic_validation_http_status" => Some("status_code"),
        "network_transport" => Some("transport_code"),
        "network_validated" | "network_metered" | "battery_saver" | "device_idle" => Some("boolean"),
        "vpn_state" => Some("service_state_code"),
        _ => None,
    }
}

fn valid_metric_value(metric: &str, value: f64) -> bool {
    match metric {
        "rtt_ms" | "jitter_ms" | "traffic_validation_duration_ms" => value >= 0.0,
        "packet_loss_pct" => (0.0..=100.0).contains(&value),
        "traffic_validation_http_status" => (100.0..=599.0).contains(&value) && value.fract() == 0.0,
        "network_transport" => (0.0..=4.0).contains(&value) && value.fract() == 0.0,
        "network_validated" | "network_metered" | "battery_saver" | "device_idle" => {
            value == 0.0 || value == 1.0
        }
        "vpn_state" => (0.0..=5.0).contains(&value) && value.fract() == 0.0,
        _ => false,
    }
}

fn validate(record: TelemetryRecord) -> Result<AdmittedTelemetry, String> {
    if record.schema_version != 1 {
        return Err("unsupported telemetry schema_version".into());
    }
    if record.event_id.trim().is_empty() || record.event_id.len() > 128 {
        return Err("event_id must contain 1..128 bytes".into());
    }
    if !valid_metric(&record.metric) {
        return Err("unknown telemetry metric".into());
    }
    let expected = expected_unit(&record.metric)
        .ok_or_else(|| "telemetry metric has no unit contract".to_string())?;
    if record.unit.as_deref() != Some(expected) {
        return Err("telemetry unit does not match metric contract".into());
    }
    if !matches!(record.outcome.as_str(), "observed" | "success" | "failure" | "unavailable" | "observation_failure" | "unknown_outcome") {
        return Err("unknown telemetry outcome".into());
    }
    if let Some(value) = record.value {
        if !value.is_finite() {
            return Err("telemetry value must be finite".into());
        }
        if !valid_metric_value(&record.metric, value) {
            return Err("telemetry value is outside metric contract".into());
        }
        if record.missing_reason.is_some() {
            return Err("missing_reason must be absent when value is present".into());
        }
    } else if record.missing_reason.as_deref().unwrap_or("").trim().is_empty() {
        return Err("missing value requires an explicit missing_reason".into());
    }
    if record.restricted_context && record.value.is_some() {
        // Restricted-context measurements may be retained for diagnostics, but
        // never admitted as primary prediction evidence.
    }
    let (group, source_admitted) = independence_group(&record.source);
    // Only actual measurements with a classified outcome can enter primary
    // evidence. Missing/unavailable/unknown outcomes remain diagnostic records.
    let outcome_admitted = matches!(record.outcome.as_str(), "observed" | "success" | "failure");
    let admitted_to_primary = source_admitted
        && !record.restricted_context
        && record.value.is_some()
        && outcome_admitted;
    Ok(AdmittedTelemetry {
        schema_version: record.schema_version,
        event_id: record.event_id,
        elapsed_realtime_ms: record.elapsed_realtime_ms,
        metric: record.metric,
        value: record.value,
        unit: record.unit,
        outcome: record.outcome,
        missing_reason: record.missing_reason,
        restricted_context: record.restricted_context,
        source: record.source,
        independence_group: group.into(),
        admitted_to_primary,
    })
}

fn buffer() -> &'static Mutex<VecDeque<AdmittedTelemetry>> {
    TELEMETRY.get_or_init(|| Mutex::new(VecDeque::with_capacity(MAX_RECORDS)))
}

pub fn ingest(record_json: &str) -> Result<(), String> {
    if record_json.len() > 16 * 1024 {
        return Err("telemetry record exceeds 16 KiB".into());
    }
    let record: TelemetryRecord =
        serde_json::from_str(record_json).map_err(|_| "invalid telemetry JSON/schema".to_string())?;
    let admitted = validate(record)?;
    let mut records = buffer().lock();
    if records.len() == MAX_RECORDS {
        records.pop_front();
    }
    records.push_back(admitted);
    Ok(())
}

pub fn snapshot() -> Result<String, String> {
    let records = buffer().lock();
    serde_json::to_string(&*records).map_err(|_| "failed to serialize telemetry snapshot".into())
}

pub fn count() -> u32 {
    buffer().lock().len() as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    fn json(value: &str, missing: &str, source: &str, restricted: bool) -> String {
        format!(
            r#"{{"schema_version":1,"event_id":"evt-1","elapsed_realtime_ms":42,"metric":"rtt_ms","value":{},"unit":"ms","outcome":"observed","missing_reason":{},"restricted_context":{},"source":"{}"}}"#,
            value, missing, restricted, source
        )
    }

    fn parse(raw: &str) -> Result<AdmittedTelemetry, String> {
        let record: TelemetryRecord =
            serde_json::from_str(raw).map_err(|_| "invalid telemetry JSON/schema".to_string())?;
        validate(record)
    }

    #[test]
    fn missing_is_not_zero_and_requires_reason() {
        let admitted = parse(&json("null", r#""not_measured""#, "health_monitor", false)).unwrap();
        assert_eq!(admitted.value, None);
        assert_eq!(admitted.missing_reason.as_deref(), Some("not_measured"));
        assert!(!admitted.admitted_to_primary);
        assert!(parse(&json("null", "null", "health_monitor", false)).is_err());
    }

    #[test]
    fn unknown_source_is_unclassified_and_not_primary() {
        let admitted = parse(&json("12.5", "null", "unknown_probe", false)).unwrap();
        assert_eq!(admitted.independence_group, "unclassified");
        assert!(!admitted.admitted_to_primary);
    }

    #[test]
    fn restricted_context_is_not_primary() {
        let admitted = parse(&json("12.5", "null", "health_monitor", true)).unwrap();
        assert!(!admitted.admitted_to_primary);
    }

    #[test]
    fn enforces_metric_units_and_value_ranges() {
        let mut raw = json("1.0", "null", "health_monitor", false);
        raw = raw.replace(r#""metric":"rtt_ms""#, r#""metric":"vpn_state""#)
            .replace(r#""unit":"ms""#, r#""unit":"service_state_code""#);
        assert!(parse(&raw).is_ok());

        let wrong_unit = raw.replace(r#""unit":"service_state_code""#, r#""unit":"ms""#);
        assert!(parse(&wrong_unit).is_err());

        let invalid_state = raw.replace(r#""value":1.0"#, r#""value":6.0"#);
        assert!(parse(&invalid_state).is_err());

        let invalid_loss = raw.replace(r#""metric":"vpn_state""#, r#""metric":"packet_loss_pct""#)
            .replace(r#""unit":"service_state_code""#, r#""unit":"percent""#)
            .replace(r#""value":1.0"#, r#""value":101.0"#);
        assert!(parse(&invalid_loss).is_err());
    }

    #[test]
    fn rejects_nan_and_unknown_fields() {
        assert!(parse(&json("NaN", "null", "health_monitor", false)).is_err());
        let raw = r#"{"schema_version":1,"event_id":"x","elapsed_realtime_ms":1,"metric":"rtt_ms","value":1.0,"unit":"ms","outcome":"observed","missing_reason":null,"restricted_context":false,"source":"health_monitor","ssid":"private"}"#;
        assert!(parse(raw).is_err());
    }

    #[test]
    fn observation_failure_is_not_primary_evidence() {
        let raw = r#"{"schema_version":1,"event_id":"evt-2","elapsed_realtime_ms":43,"metric":"rtt_ms","value":null,"unit":"ms","outcome":"observation_failure","missing_reason":"probe_cancelled","restricted_context":false,"source":"health_monitor"}"#;
        let admitted = parse(raw).unwrap();
        assert!(!admitted.admitted_to_primary);
        assert_eq!(admitted.outcome, "observation_failure");
    }

    #[test]
    fn unavailable_and_unknown_outcomes_are_not_primary() {
        for outcome in ["unavailable", "unknown_outcome"] {
            let raw = format!(
                r#"{{"schema_version":1,"event_id":"evt-3","elapsed_realtime_ms":44,"metric":"rtt_ms","value":1.0,"unit":"ms","outcome":"{}","missing_reason":null,"restricted_context":false,"source":"health_monitor"}}"#,
                outcome
            );
            assert!(!parse(&raw).unwrap().admitted_to_primary, "{outcome}");
        }
    }
}
