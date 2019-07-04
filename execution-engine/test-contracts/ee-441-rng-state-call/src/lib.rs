#![no_std]
#![feature(alloc, cell_update)]

#[macro_use]
extern crate alloc;
extern crate cl_std;

use alloc::string::String;

use cl_std::contract_api;
use cl_std::contract_api::pointers::ContractPointer;
use cl_std::key::Key;
use cl_std::uref::URef;
use cl_std::value::U512;
use cl_std::contract_api::{get_arg, new_uref, add_uref};

const DO_NOTHING_FN: [u8; 32] =
    [67, 199, 39, 112, 64, 242, 105, 254, 209, 5, 0, 85, 218, 155, 248, 45, 212, 192, 105, 176, 205, 117, 123, 114, 204, 233, 173, 136, 98, 236, 4, 164] ;

const DO_SOMETHING_FN: [u8; 32] =
    [181, 17, 58, 89, 64, 111, 110, 96, 183, 14, 31, 165, 120, 145, 22, 58, 105, 214, 212, 173, 87, 17, 118, 228, 117, 206, 29, 137, 203, 240, 4, 143];


#[no_mangle]
pub extern "C" fn call() {
    let do_nothing = ContractPointer::Hash(DO_NOTHING_FN);
    let do_something = ContractPointer::Hash(DO_SOMETHING_FN);
//
    let flag: String = get_arg(0);

    if flag == "pass1" {
        // Two calls should forward the internal RNG. This pass is a baseline.
        let uref1 : URef = new_uref(U512::from(0)).into();
        let uref2 : URef = new_uref(U512::from(1)).into();
        add_uref("uref1", &Key::URef(uref1));
        add_uref("uref2", &Key::URef(uref2));
    }
    else if flag == "pass2" {
        let uref1 : URef = new_uref(U512::from(0)).into();
        add_uref("uref1", &Key::URef(uref1));
        // do_nothing doesn't do anything. It SHOULD not forward the internal RNG.
        let result: String = contract_api::call_contract(do_nothing.clone(), &(), &vec![]);
        assert_eq!(result, "Hello, world!");
        let uref2 : URef = new_uref(U512::from(1)).into();
        add_uref("uref2", &Key::URef(uref2));
    }
    else if flag == "pass3" {
        let uref1 : URef = new_uref(U512::from(0)).into();
        add_uref("uref1", &Key::URef(uref1));
        // do_something returns a new uref, and it should forward the internal RNG.
        let uref2: URef = contract_api::call_contract(do_something.clone(), &(), &vec![]);
        add_uref("uref2", &Key::URef(uref2));
    }
    else {
        contract_api::revert(66);
    }
}
