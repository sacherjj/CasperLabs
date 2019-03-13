use crate::bytesrepr::ToBytes;
use alloc::alloc::{Alloc, Global};
use alloc::vec::Vec;

#[allow(clippy::zero_ptr)]
pub fn alloc_bytes(n: usize) -> *mut u8 {
    if n == 0 {
        // cannot allocate with size 0
        0 as *mut u8
    } else {
        Global.alloc_array(n).unwrap().as_ptr()
    }
}

// I don't know why I need a special version of to_ptr for
// &str, but the compiler complains if I try to use the polymorphic
// version with T = str.
pub fn str_ref_to_ptr(t: &str) -> (*const u8, usize, Vec<u8>) {
    let bytes = t.to_bytes();
    let ptr = bytes.as_ptr();
    let size = bytes.len();
    (ptr, size, bytes)
}

pub fn to_ptr<T: ToBytes>(t: &T) -> (*const u8, usize, Vec<u8>) {
    let bytes = t.to_bytes();
    let ptr = bytes.as_ptr();
    let size = bytes.len();
    (ptr, size, bytes)
}
