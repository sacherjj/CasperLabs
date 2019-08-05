#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api;
use cl_std::value::account::PurseId;

#[no_mangle]
pub extern "C" fn call() {
    let known_main_purse: PurseId = contract_api::get_arg(0);
    let main_purse: PurseId = contract_api::main_purse();
    assert_eq!(
        main_purse, known_main_purse,
        "main purse was not known purse"
    );
}
