//! A library to support testing of Wasm smart contracts for use on the CasperLabs network.

#![warn(missing_docs)]

mod code;
mod error;
#[doc(hidden)]
pub mod low_level;
mod session;
mod test_context;
mod value;

pub use code::Code;
pub use error::{Error, Result};
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

/// Default test account address.
pub const DEFAULT_ACCOUNT_ADDR: [u8; 32] = [6u8; 32];

/// Default initial balance of a test account in
/// [`Motes`](https://docs.rs/casperlabs-engine-shared/latest/casperlabs_engine_shared/motes/struct.Motes.html).
pub const DEFAULT_ACCOUNT_INITIAL_BALANCE: u64 = 100_000_000_000;
