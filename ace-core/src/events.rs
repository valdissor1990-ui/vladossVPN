/// Minimal Stage B event set. Real telemetry events arrive in Stage C.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AceEvent {
    Test,
    Initialized,
    Shutdown,
}
