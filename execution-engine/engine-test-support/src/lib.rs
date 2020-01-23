//! A library to support testing of Wasm smart contracts for use on the CasperLabs network.
//!
//! # Example
//! Consider a contract held in "contract.wasm" which stores an arbitrary `String` under a `Key`
//! named "special_value":
//! ```no_run
//! # use contract::{contract_api::{runtime, storage}, unwrap_or_revert::UnwrapOrRevert};
//! # use types::{Key, ApiError};
//! const KEY: &str = "special_value";
//!
//! #[no_mangle]
//! pub extern "C" fn call() {
//!     let value: String = runtime::get_arg(0)
//!         .unwrap_or_revert_with(ApiError::MissingArgument)
//!         .unwrap_or_revert_with(ApiError::InvalidArgument);
//!
//!     let value_ref = storage::new_turef(value);
//!     let value_key: Key = value_ref.into();
//!     runtime::put_key(KEY, value_key);
//! }
//! ```
//!
//! The test could be written as follows:
//! ```no_run
//! use casperlabs_engine_test_support::{TestContextBuilder, SessionBuilder, Value, Error, Code};
//!
//! const MY_ACCOUNT: [u8; 32] = [7u8; 32];
//! const KEY: &str = "special_value";
//! const VALUE: &str = "hello world";
//!
//! let mut context = TestContextBuilder::new()
//!     .with_account(MY_ACCOUNT, 128_000_000.into())
//!     .build();
//!
//! // The test framework checks for compiled Wasm files in '<current working dir>/wasm'.  Paths
//! // relative to the current working dir (e.g. 'wasm/contract.wasm') can also be used, as can
//! // absolute paths.
//! let session_code = Code::from("contract.wasm");
//! let session_args = (VALUE,);
//! let session = SessionBuilder::new(session_code, session_args)
//!     .with_address(MY_ACCOUNT)
//!     .with_authorization_keys(&[MY_ACCOUNT])
//!     .build();
//!
//! let result_of_query: Result<Value, Error> = context
//!     .run(session)
//!     .query(MY_ACCOUNT, &[KEY]);
//!
//! let returned_value = result_of_query.expect("should be a value");
//!
//! let expected_value = Value::from_t(VALUE.to_string()).expect("should construct Value");
//! assert_eq!(expected_value, returned_value);
//! ```

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
