//! Contains support for writing smart contracts.

pub mod account;
pub mod runtime;
pub mod storage;
pub mod system;

use alloc::{
    alloc::{AllocRef, Global, Layout},
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
        let layout = Layout::array::<u8>(n)
            .map_err(|_| ApiError::AllocLayout)
            .unwrap_or_revert();
        Global
            .alloc(layout)
            .map(|(non_null_ptr, _size)| non_null_ptr)
            .map_err(|_| ApiError::OutOfMemory)
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
