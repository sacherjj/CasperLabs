#![feature(never_type, result_map_or_else)]

// core dependencies
extern crate core;

// third-party dependencies
#[macro_use]
extern crate failure;
extern crate lmdb;
extern crate parking_lot;
extern crate wasmi;

// local dependencies
extern crate common;
extern crate shared;

// third-party dev-dependencies
#[cfg(test)]
#[macro_use]
extern crate lazy_static;
#[cfg(test)]
extern crate proptest;
#[cfg(test)]
extern crate tempfile;

// modules
pub mod error;
pub mod global_state;
pub mod trie;
pub mod trie_store;

#[cfg(test)]
lazy_static! {
    // 10 MiB = 10485760 bytes
    // page size on x86_64 linux = 4096 bytes
    // 10485760 / 4096 = 2560
    static ref TEST_MAP_SIZE: usize = {
        let page_size = shared::os::get_page_size().unwrap();
        page_size * 2560
    };
}
