use thiserror::Error;

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum AceError {
    #[error("ACE not initialized")]
    NotInitialized,
    #[error("Panic caught at FFI boundary: {0}")]
    Panic(String),
    #[error("Internal error: {0}")]
    Internal(String),
}
