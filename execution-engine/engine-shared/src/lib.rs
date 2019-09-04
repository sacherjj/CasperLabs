#![feature(result_map_or_else, never_type)]

extern crate blake2;
extern crate chrono;
extern crate contract_ffi;
#[macro_use]
extern crate lazy_static;
extern crate base16;
extern crate libc;
extern crate log;
extern crate num;
extern crate parity_wasm;

#[macro_use]
pub mod logging;
pub mod newtypes;
pub mod os;
pub mod semver;
pub mod socket;
pub mod test_utils;
pub mod transform;
pub mod utils;
