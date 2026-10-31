use thiserror::Error;

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum AceError {
    #[error("ACE not initialized")]
    NotInitialized,
    #[error("Panic caught at FFI boundary")]
    Panic,
    #[error("Internal ACE error")]
    Internal,
}

// UDL declares a flat error enum, so Rust variants intentionally carry no
// payload. Detailed panic/callback information is written to the native log.
impl From<uniffi::UnexpectedUniFFICallbackError> for AceError {
    fn from(e: uniffi::UnexpectedUniFFICallbackError) -> Self {
        log::error!("Unexpected UniFFI callback error: {}", e);
        AceError::Internal
    }
}
