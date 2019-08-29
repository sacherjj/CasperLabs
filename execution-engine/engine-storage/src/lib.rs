#![feature(never_type, result_map_or_else)]

#[cfg(test)]
use lazy_static::lazy_static;

// modules
pub mod error;
pub mod global_state;
pub mod protocol_data;
pub mod transaction_source;
pub mod trie;
pub mod trie_store;

#[cfg(test)]
lazy_static! {
    // 10 MiB = 10485760 bytes
    // page size on x86_64 linux = 4096 bytes
    // 10485760 / 4096 = 2560
    static ref TEST_MAP_SIZE: usize = {
        let page_size = engine_shared::os::get_page_size().unwrap();
        page_size * 2560
    };
}
