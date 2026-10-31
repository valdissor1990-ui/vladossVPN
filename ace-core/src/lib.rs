//! Vladoss ACE Control Plane — Stage B smoke foundation.
//! Real UniFFI exports. No Telemetry / Digital Twin / NetworkExecutor.

uniffi::include_scaffolding!("ace_core");

mod error;
mod panic_guard;
mod telemetry;
mod version;

pub use error::AceError;
pub use version::VERSION;

use once_cell::sync::OnceCell;
use parking_lot::RwLock;
use std::sync::{Arc, Mutex};

static STATE: OnceCell<RwLock<AceState>> = OnceCell::new();

struct AceState {
    // UDL callback interfaces are represented as Box<dyn Trait> by UniFFI 0.28.
    listener: Option<Box<dyn AceListener>>,
}

/// Implemented on the Kotlin side in process `:ace`.
pub trait AceListener: Send + Sync {
    fn on_event(&self, event: String, payload: String);
}

/// Marker string proves the call reached Rust .so (not a Kotlin stub).
pub fn ace_version() -> String {
    format!(
        "ace-core {} (rust, target={})",
        VERSION,
        std::env::consts::ARCH
    )
}

pub fn ace_initialize(listener: Option<Box<dyn AceListener>>) -> Result<(), AceError> {
    panic_guard::guard(|| {
        let state = STATE.get_or_init(|| RwLock::new(AceState { listener: None }));
        let mut lock = state.write();
        lock.listener = listener;
        log::info!("ACE initialized {}", ace_version());
        if let Some(ref listener) = lock.listener {
            listener.on_event("Initialized".into(), ace_version());
        }
        Ok(())
    })
}

pub fn ace_shutdown() -> Result<(), AceError> {
    panic_guard::guard(|| {
        if let Some(state) = STATE.get() {
            let mut lock = state.write();
            if let Some(ref listener) = lock.listener {
                listener.on_event("Shutdown".into(), String::new());
            }
            lock.listener = None;
        }
        log::info!("ACE shutdown");
        Ok(())
    })
}

pub fn ace_ping() -> Result<String, AceError> {
    panic_guard::guard(|| Ok(format!("pong from {}", ace_version())))
}

pub fn ace_emit_test_event(payload: String) -> Result<(), AceError> {
    panic_guard::guard(|| {
        let state = STATE.get().ok_or(AceError::NotInitialized)?;
        let lock = state.read();
        let listener = lock.listener.as_ref().ok_or(AceError::NotInitialized)?;
        listener.on_event("Test".into(), payload);
        Ok(())
    })
}


/// Admit one versioned telemetry record into the bounded Stage C memory window.
/// No persistence, prediction, or control action is performed.
pub fn ace_ingest_telemetry(record_json: String) -> Result<(), AceError> {
    panic_guard::guard(|| telemetry::ingest(&record_json).map_err(|e| {
        log::warn!("Rejected ACE telemetry record: {}", e);
        AceError::Internal
    }))
}

/// Return the current bounded in-memory telemetry window as JSON.
pub fn ace_telemetry_snapshot() -> Result<String, AceError> {
    panic_guard::guard(|| telemetry::snapshot().map_err(|e| {
        log::error!("ACE telemetry snapshot failed: {}", e);
        AceError::Internal
    }))
}

/// Number of records currently held in the in-memory telemetry window.
pub fn ace_telemetry_count() -> Result<u32, AceError> {
    panic_guard::guard(|| Ok(telemetry::count()))
}

pub fn ace_trigger_test_panic() -> Result<(), AceError> {
    panic_guard::guard(|| {
        panic!("intentional panic for Stage B smoke test");
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    // STATE is process-global; serialize tests that initialize or shut it down.
    static STATE_TEST_LOCK: Mutex<()> = Mutex::new(());

    struct TestListener {
        last: Arc<Mutex<Option<(String, String)>>>,
    }

    impl AceListener for TestListener {
        fn on_event(&self, event: String, payload: String) {
            *self.last.lock().unwrap() = Some((event, payload));
        }
    }

    #[test]
    fn version_marks_rust() {
        let v = ace_version();
        assert!(v.contains("ace-core"), "{v}");
        assert!(v.contains("(rust, target="), "{v}");
    }

    #[test]
    fn ping_works() {
        let p = ace_ping().unwrap();
        assert!(p.starts_with("pong"), "{p}");
        assert!(p.contains("(rust, target="), "{p}");
    }

    #[test]
    fn init_emit_shutdown() {
        let _guard = STATE_TEST_LOCK.lock().unwrap();
        let last = Arc::new(Mutex::new(None));
        let listener = Box::new(TestListener { last: last.clone() });
        ace_initialize(Some(listener)).unwrap();
        ace_emit_test_event("hello".into()).unwrap();
        let event = last.lock().unwrap().clone();
        assert_eq!(event.as_ref().map(|x| x.0.as_str()), Some("Test"));
        assert_eq!(event.as_ref().map(|x| x.1.as_str()), Some("hello"));
        ace_shutdown().unwrap();
    }

    #[test]
    fn emit_without_init_fails() {
        let _guard = STATE_TEST_LOCK.lock().unwrap();
        let _ = ace_shutdown();
        let err = ace_emit_test_event("x".into()).unwrap_err();
        assert!(matches!(err, AceError::NotInitialized));
    }

    #[test]
    fn trigger_panic_is_caught() {
        let err = ace_trigger_test_panic().unwrap_err();
        assert!(matches!(err, AceError::Panic));
    }
}
