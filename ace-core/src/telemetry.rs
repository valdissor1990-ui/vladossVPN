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
    if !matches!(record.outcome.as_str(), "observed" | "success" | "failure" | "unavailable" | "observation_failure" | "unknown_outcome") {
        return Err("unknown telemetry outcome".into());
    }
    if let Some(value) = record.value {
        if !value.is_finite() {
            return Err("telemetry value must be finite".into());
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
    let admitted_to_primary =
        source_admitted && !record.restricted_context && record.outcome != "observation_failure";
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

    #[test]
    fn missing_is_not_zero_and_requires_reason() {
        let raw = json("null", r#""not_measured""#, "health_monitor", false);
        ingest(&raw).unwrap();
        let out = snapshot().unwrap();
        assert!(out.contains(r#""value":null"#));
        assert!(out.contains(r#""missing_reason":"not_measured""#));
    }

    #[test]
    fn unknown_source_is_unclassified_and_not_primary() {
        let raw = json("12.5", "null", "unknown_probe", false);
        ingest(&raw).unwrap();
        let out = snapshot().unwrap();
        assert!(out.contains(r#""independence_group":"unclassified""#));
        assert!(out.contains(r#""admitted_to_primary":false"#));
    }

    #[test]
    fn restricted_context_is_not_primary() {
        let raw = json("12.5", "null", "health_monitor", true);
        ingest(&raw).unwrap();
        let out = snapshot().unwrap();
        assert!(out.contains(r#""admitted_to_primary":false"#));
    }

    #[test]
    fn rejects_nan_and_unknown_fields() {
        let raw = json("NaN", "null", "health_monitor", false);
        assert!(ingest(&raw).is_err());
        let raw = r#"{"schema_version":1,"event_id":"x","elapsed_realtime_ms":1,"metric":"rtt_ms","value":1.0,"unit":"ms","outcome":"observed","missing_reason":null,"restricted_context":false,"source":"health_monitor","ssid":"private"}"#;
        assert!(ingest(raw).is_err());
    }

    #[test]
    fn observation_failure_is_not_primary_evidence() {
        let raw = r#"{"schema_version":1,"event_id":"evt-2","elapsed_realtime_ms":43,"metric":"rtt_ms","value":null,"unit":"ms","outcome":"observation_failure","missing_reason":"probe_cancelled","restricted_context":false,"source":"health_monitor"}"#;
        ingest(raw).unwrap();
        assert!(snapshot().unwrap().contains(r#""admitted_to_primary":false"#));
    }
}
