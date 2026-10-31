package com.vpnproxy.ace.ipc;

/**
 * One-way observational telemetry ingress into the ACE process.
 * Payload is schema-validated by Rust; no VPN control methods exist here.
 */
oneway interface IAceTelemetryReceiver {
    void onTelemetryRecord(String recordJson);
}
