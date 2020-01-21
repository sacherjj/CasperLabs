//! A library to support testing of Wasm smart contracts for use on the CasperLabs network.

// TODO(Fraser) - remove
#![allow(unused)]
#![warn(missing_docs)]

mod code;
mod error;
#[doc(hidden)]
pub mod low_level;
mod query;
mod session;
mod test_context;
mod value;

pub use code::Code;
pub use error::Error;
pub use query::Query;
pub use session::{Session, SessionBuilder};
pub use test_context::{TestContext, TestContextBuilder};
pub use value::Value;

/// An address of an entity (e.g. an account or key) on the network.
pub type Address = [u8; 32];

/// The address of a
/// [`URef`](https://docs.rs/casperlabs-types/latest/casperlabs_types/struct.URef.html) (unforgeable
/// reference) on the network.
pub type URefAddr = [u8; 32];

/// The hash of a smart contract stored on the network, which can be used to reference the contract.
pub type Hash = [u8; 32];
