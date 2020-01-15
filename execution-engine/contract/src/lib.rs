//! A Rust library for writing smart contracts on the [CasperLabs Platform](https://techspec.casperlabs.io).
//!
//! # Example
//!
//! The following example contains session code which persists an integer value under an unforgeable
//! reference.  It then stores the unforgeable reference under a name in context-local storage.
//!
//! ```rust,no_run
//! #![no_std]
//!
//! use casperlabs_contract::{
//!     contract_api::{runtime, storage, TURef},
//!     unwrap_or_revert::UnwrapOrRevert,
//! };
//! use casperlabs_types::{ApiError, Key, URef};
//!
//! const KEY: &str = "special_value";
//!
//! fn store(value: i32) {
//!     // Store `value` under a new unforgeable reference.
//!     let value_ref: TURef<i32> = storage::new_turef(value);
//!
//!     // Wrap the unforgeable reference in a value of type `Key`.
//!     let value_key: Key = value_ref.into();
//!
//!     // Store this key under the name "special_value" in context-local storage.
//!     runtime::put_key(KEY, value_key);
//! }
//!
//! // All session code must have a `call` entrypoint.
//! #[no_mangle]
//! pub extern "C" fn call() {
//!     // Get the optional first argument supplied to the argument.
//!     let value: i32 = runtime::get_arg(0)
//!         // Unwrap the `Option`, returning an error if there was no argument supplied.
//!         .unwrap_or_revert_with(ApiError::MissingArgument)
//!         // Unwrap the `Result` containing the deserialized argument or return an error
//!         // if there was a deserialization error.
//!         .unwrap_or_revert_with(ApiError::InvalidArgument);
//!
//!     store(value);
//! }
//! # fn main() {}
//! ```
//!
//! # Writing Smart Contracts
//! Support for writing smart contracts are contained in the [`contract_api`](crate::contract_api)
//! module and its submodules.

#![cfg_attr(not(feature = "std"), no_std)]
#![feature(alloc_error_handler, allocator_api, core_intrinsics, lang_items)]

extern crate alloc;
#[cfg(any(feature = "std", test))]
#[macro_use]
extern crate std;

#[cfg(not(any(feature = "std", test)))]
#[global_allocator]
pub static ALLOC: wee_alloc::WeeAlloc = wee_alloc::WeeAlloc::INIT;

pub mod args_parser;
pub mod contract_api;
pub mod ext_ffi;
#[cfg(not(any(feature = "std", test)))]
pub mod handlers;
pub mod unwrap_or_revert;
