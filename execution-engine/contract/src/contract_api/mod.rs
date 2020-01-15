//! Contains support for writing smart contracts.

pub mod account;
pub mod runtime;
pub mod storage;
pub mod system;
mod turef;

pub use turef::TURef;

use alloc::{
    alloc::{Alloc, Global},
    vec::Vec,
};

use casperlabs_types::{bytesrepr::ToBytes, ApiError};

use crate::unwrap_or_revert::UnwrapOrRevert;

#[allow(clippy::zero_ptr)]
fn alloc_bytes(n: usize) -> *mut u8 {
    if n == 0 {
        // cannot allocate with size 0
        0 as *mut u8
    } else {
        Global
            .alloc_array(n)
            .map_err(|_| ApiError::OutOfMemoryError)
            .unwrap_or_revert()
            .as_ptr()
    }
}

fn to_ptr<T: ToBytes>(t: T) -> (*const u8, usize, Vec<u8>) {
    let bytes = t.into_bytes().unwrap_or_revert();
    let ptr = bytes.as_ptr();
    let size = bytes.len();
    (ptr, size, bytes)
}
