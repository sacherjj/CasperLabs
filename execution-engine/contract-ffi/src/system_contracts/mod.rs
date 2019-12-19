//! Contains support for implementing system contracts.
//!
//! Each submodule contains support code and logic that is shared between host
//! and the system contract .
mod error;
pub mod mint;
pub mod pos;
pub mod system_contract;

pub use error::Error;
pub use system_contract::SystemContract;
