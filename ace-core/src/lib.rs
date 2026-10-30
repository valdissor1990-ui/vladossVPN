//! Vladoss ACE Control Plane — Stage B smoke foundation.
//! Real UniFFI exports. No Telemetry / Digital Twin / NetworkExecutor.

uniffi::include_scaffolding!("ace_core");

mod error;
mod panic_guard;
mod version;

pub use error::AceError;
pub use version::VERSION;

use once_cell::sync::OnceCell;
use parking_lot::RwLock;
use std::sync::Arc;

static STATE: OnceCell<RwLock<AceState>> = OnceCell::new();

struct AceState {
    listener: Option<Arc<dyn AceListener>>,
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

pub fn ace_initialize(listener: Option<Arc<dyn AceListener>>) -> Result<(), AceError> {
    panic_guard::guard(|| {
        let state = STATE.get_or_init(|| RwLock::new(AceState { listener: None }));
        let mut lock = state.write();
        lock.listener = listener;
        log::info!("ACE initialized {}", ace_version());
        if let Some(ref l) = lock.listener {
            l.on_event("Initialized".into(), ace_version());
        }
        Ok(())
    })
}

pub fn ace_shutdown() -> Result<(), AceError> {
    panic_guard::guard(|| {
        if let Some(state) = STATE.get() {
            let mut lock = state.write();
            if let Some(ref l) = lock.listener {
                l.on_event("Shutdown".into(), String::new());
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
        let listener = STATE
            .get()
            .and_then(|s| s.read().listener.clone())
            .ok_or(AceError::NotInitialized)?;
        listener.on_event("Test".into(), payload);
        Ok(())
    })
}

pub fn ace_trigger_test_panic() -> Result<(), AceError> {
    panic_guard::guard(|| {
        panic!("intentional panic for Stage B smoke test");
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    struct TestListener {
        last: Mutex<Option<(String, String)>>,
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
        let listener = Arc::new(TestListener {
            last: Mutex::new(None),
        });
        ace_initialize(Some(listener.clone())).unwrap();
        ace_emit_test_event("hello".into()).unwrap();
        let last = listener.last.lock().unwrap().clone();
        assert_eq!(last.as_ref().map(|x| x.0.as_str()), Some("Test"));
        assert_eq!(last.as_ref().map(|x| x.1.as_str()), Some("hello"));
        ace_shutdown().unwrap();
    }

    #[test]
    fn emit_without_init_fails() {
        let _ = ace_shutdown();
        // Re-init clean: clear by shutdown already
        let err = ace_emit_test_event("x".into()).unwrap_err();
        assert!(matches!(err, AceError::NotInitialized));
    }

    #[test]
    fn trigger_panic_is_caught() {
        let err = ace_trigger_test_panic().unwrap_err();
        assert!(matches!(err, AceError::Panic(_)));
    }
}
