pub mod account;
mod contract_ref;
mod error;
pub mod runtime;
pub mod storage;
pub mod system;
mod turef;

use alloc::alloc::{Alloc, Global};
use alloc::vec::Vec;

use crate::bytesrepr::ToBytes;
use crate::unwrap_or_revert::UnwrapOrRevert;
pub use contract_ref::ContractRef;
pub use error::{i32_from, result_from, Error};
pub use turef::TURef;

#[allow(clippy::zero_ptr)]
fn alloc_bytes(n: usize) -> *mut u8 {
    if n == 0 {
        // cannot allocate with size 0
        0 as *mut u8
    } else {
        Global
            .alloc_array(n)
            .map_err(|_| Error::OutOfMemoryError)
            .unwrap_or_revert()
            .as_ptr()
    }
}

// I don't know why I need a special version of to_ptr for
// &str, but the compiler complains if I try to use the polymorphic
// version with T = str.
fn str_ref_to_ptr(t: &str) -> (*const u8, usize, Vec<u8>) {
    let bytes = t.to_bytes().unwrap_or_revert();
    let ptr = bytes.as_ptr();
    let size = bytes.len();
    (ptr, size, bytes)
}

fn to_ptr<T: ToBytes>(t: &T) -> (*const u8, usize, Vec<u8>) {
    let bytes = t.to_bytes().unwrap_or_revert();
    let ptr = bytes.as_ptr();
    let size = bytes.len();
    (ptr, size, bytes)
}
