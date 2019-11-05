#![no_std]
#![feature(
    allocator_api,
    core_intrinsics,
    lang_items,
    alloc_error_handler,
    try_reserve
)]

#[macro_use]
extern crate alloc;
extern crate binascii;
#[macro_use]
extern crate bitflags;
#[macro_use]
extern crate failure;
extern crate hex_fmt;
#[macro_use]
extern crate num_derive;
#[cfg(any(test, feature = "gens"))]
extern crate proptest;
#[macro_use]
extern crate uint;
extern crate wee_alloc;

#[cfg(not(feature = "std"))]
#[global_allocator]
pub static ALLOC: wee_alloc::WeeAlloc = wee_alloc::WeeAlloc::INIT;

pub mod args_parser;
pub mod base16;
pub mod bytesrepr;
pub mod contract_api;
pub mod execution;
pub mod ext_ffi;
#[cfg(any(test, feature = "gens"))]
pub mod gens;
#[cfg(not(feature = "std"))]
pub mod handlers;
pub mod key;
pub mod system_contracts;
pub mod unwrap_or_revert;
pub mod uref;
pub mod value;
