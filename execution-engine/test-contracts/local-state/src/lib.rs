#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;
use alloc::string::String;
use cl_std::contract_api::{read_local, write_local};

#[no_mangle]
pub extern "C" fn call() {
    let maybe_s: Option<String> = read_local([66; 32]);
    assert!(maybe_s.is_none());

    // Write "Hello, "
    write_local([66u8; 32], String::from("Hello, "));

    // Read
    let mut res: String = read_local([66u8; 32]).unwrap();
    assert_eq!(res, String::from("Hello, "));

    // Append
    res.push_str("world!");
    write_local([66u8; 32], res);

    // Read
    let res: String = read_local([66u8; 32]).unwrap();
    assert_eq!(res, String::from("Hello, world!"));
}
