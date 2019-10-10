//! Supporting code for system contract implementation.
//!
//! Submodules contains supporting code and logic that is shared between host
//! and the contract implementation.
//!
//! Naming of the modules is related to actual system contract that is user of
//! the supporting code i.e. mint.
mod error;
pub mod mint;
pub mod pos;
pub mod system_contract;

pub use error::Error;
pub use system_contract::SystemContract;
