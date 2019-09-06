#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;
extern crate pos;

use alloc::collections::BTreeMap;
use contract_ffi::contract_api;
use contract_ffi::contract_api::pointers::{ContractPointer, UPointer};
use contract_ffi::uref::{AccessRights, URef};
use contract_ffi::value::account::PurseId;

#[repr(u32)]
enum Error {
    MintFailure = 0,
}

#[no_mangle]
pub extern "C" fn pos_ext() {
    pos::delegate();
}

#[no_mangle]
pub extern "C" fn call() {
    let mint_uref: URef = contract_api::get_arg(0);
    let mint = ContractPointer::URef(UPointer::new(mint_uref.addr(), AccessRights::READ));
    
    // TODO: use the mint to make the purses, and whatever else the pos genesis effects currently do

    let contract = contract_api::fn_by_name("pos_ext", BTreeMap::new());
    let uref: URef = contract_api::new_uref(contract).into();

    contract_api::ret(&uref, &vec![uref]);
}


fn mint_purse(mint: ContractPointer) -> PurseId {
    // TODO
    PurseId::new(URef::new([0u8; 32], AccessRights::READ))
}
