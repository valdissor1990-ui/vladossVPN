//! Vladoss ACE Control Plane — Stage B smoke foundation.
//!
//! Scope: version, ping, initialize/shutdown, test event, panic containment.
//! No Telemetry, Digital Twin, Prediction, or NetworkExecutor.

uniffi::include_scaffolding!("ace_core");

mod error;
mod events;
mod panic_guard;
mod version;

pub use error::AceError;
pub use events::AceEvent;
pub use version::VERSION;

use once_cell::sync::OnceCell;
use parking_lot::RwLock;
use std::sync::Arc;

static STATE: OnceCell<RwLock<AceState>> = OnceCell::new();

struct AceState {
    listener: Option<Arc<dyn AceListener>>,
}

/// Foreign callback implemented on the Kotlin side (in `:ace` process).
pub trait AceListener: Send + Sync {
    fn on_event(&self, event: AceEvent, payload: String);
}

pub fn ace_version() -> String {
    VERSION.to_string()
}

pub fn ace_initialize(listener: Option<Arc<dyn AceListener>>) -> Result<(), AceError> {
    panic_guard::guard(|| {
        let state = STATE.get_or_init(|| RwLock::new(AceState { listener: None }));
        let mut lock = state.write();
        lock.listener = listener;
        log::info!("ACE initialized, version {}", VERSION);
        if let Some(ref l) = lock.listener {
            l.on_event(AceEvent::Initialized, VERSION.to_string());
        }
        Ok(())
    })
}

pub fn ace_shutdown() -> Result<(), AceError> {
    panic_guard::guard(|| {
        if let Some(state) = STATE.get() {
            let mut lock = state.write();
            if let Some(ref l) = lock.listener {
                l.on_event(AceEvent::Shutdown, String::new());
            }
            lock.listener = None;
        }
        log::info!("ACE shutdown");
        Ok(())
    })
}

pub fn ace_ping() -> Result<String, AceError> {
    panic_guard::guard(|| Ok("pong".to_string()))
}

/// Stage B only: emit a test event through the registered listener.
pub fn ace_emit_test_event(payload: String) -> Result<(), AceError> {
    panic_guard::guard(|| {
        let listener = STATE
            .get()
            .and_then(|s| s.read().listener.clone())
            .ok_or(AceError::NotInitialized)?;
        listener.on_event(AceEvent::Test, payload);
        Ok(())
    })
}

/// Stage B only: intentional panic to verify FFI containment.
pub fn ace_trigger_test_panic() -> Result<(), AceError> {
    panic_guard::guard(|| {
        panic!("intentional panic for Stage B smoke test");
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    struct TestListener;
    impl AceListener for TestListener {
        fn on_event(&self, _event: AceEvent, _payload: String) {}
    }

    #[test]
    fn version_not_empty() {
        assert!(!ace_version().is_empty());
    }

    #[test]
    fn ping_works() {
        assert_eq!(ace_ping().unwrap(), "pong");
    }

    #[test]
    fn init_and_shutdown() {
        ace_initialize(Some(Arc::new(TestListener))).unwrap();
        ace_shutdown().unwrap();
    }
}
