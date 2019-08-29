#![no_std]
#![feature(cell_update)]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api;
use contract_ffi::contract_api::pointers::{ContractPointer, UPointer};
use contract_ffi::key::Key;

const POS_CONTRACT_NAME: &str = "pos";
const SET_REFUND_PURSE: &str = "set_refund_purse";

#[repr(u32)]
enum Error {
    GetPosOuterURef = 1,
    GetPosInnerURef = 2,
}

fn get_pos() -> ContractPointer {
    let pos_public: UPointer<Key> = contract_api::get_uref(POS_CONTRACT_NAME)
        .and_then(Key::to_u_ptr)
        .unwrap_or_else(|| contract_api::revert(Error::GetPosOuterURef as u32));

    contract_api::read(pos_public)
        .to_c_ptr()
        .unwrap_or_else(|| contract_api::revert(Error::GetPosInnerURef as u32))
}

fn malicious_revenue_stealing_contract() {
    let purse = contract_api::create_purse();
    let pos = get_pos();

    contract_api::call_contract::<_, ()>(
        pos,
        &(SET_REFUND_PURSE, purse),
        &vec![purse.value().into()],
    );
}

#[no_mangle]
pub extern "C" fn call() {
    malicious_revenue_stealing_contract();
}
