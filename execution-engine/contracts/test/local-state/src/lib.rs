#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;
use alloc::string::{String, ToString};
use cl_std::contract_api::{read_local, write_local};

#[no_mangle]
pub extern "C" fn call() {
    // Appends " Hello, world!" to a [66; 32] local key with spaces trimmed.
    // Two runs should yield value "Hello, world! Hello, world!"

    let mut res: String = read_local([66; 32]).unwrap_or_default();
    res.push_str(" Hello, ");
    // Write "Hello, "
    write_local([66u8; 32], res);
    // Read (this should exercise cache)
    let mut res: String = read_local([66u8; 32]).expect("Should have local key after write");
    // Append
    res.push_str("world!");
    // Write
    write_local([66u8; 32], res.trim().to_string());
}
