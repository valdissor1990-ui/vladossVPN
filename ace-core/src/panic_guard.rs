use crate::error::AceError;
use std::panic::{catch_unwind, AssertUnwindSafe};

/// Catch panics at the FFI boundary.
/// Does **not** protect against abort / SIGSEGV / OOM (ADR-006 LIMITATION).
pub fn guard<F, T>(f: F) -> Result<T, AceError>
where
    F: FnOnce() -> Result<T, AceError>,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(result) => result,
        Err(payload) => {
            let message = if let Some(s) = payload.downcast_ref::<&str>() {
                (*s).to_string()
            } else if let Some(s) = payload.downcast_ref::<String>() {
                s.clone()
            } else {
                "unknown panic".to_string()
            };
            log::error!("Panic caught at FFI boundary: {}", message);
            Err(AceError::Panic(message))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn guard_passes_ok() {
        let result: Result<i32, AceError> = guard(|| Ok(42));
        assert_eq!(result.unwrap(), 42);
    }

    #[test]
    fn guard_catches_panic() {
        let result: Result<i32, AceError> = guard(|| panic!("boom"));
        assert!(matches!(result, Err(AceError::Panic(_))));
    }

    #[test]
    fn guard_passes_err() {
        let result: Result<i32, AceError> = guard(|| Err(AceError::NotInitialized));
        assert!(matches!(result, Err(AceError::NotInitialized)));
    }
}
