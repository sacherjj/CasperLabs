#![cfg_attr(not(feature = "std"), no_std)]
#![feature(
    allocator_api,
    core_intrinsics,
    lang_items,
    alloc_error_handler,
    try_reserve
)]

extern crate alloc;

#[cfg(not(feature = "std"))]
#[global_allocator]
pub static ALLOC: wee_alloc::WeeAlloc = wee_alloc::WeeAlloc::INIT;

pub mod args_parser;
pub mod block_time;
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
