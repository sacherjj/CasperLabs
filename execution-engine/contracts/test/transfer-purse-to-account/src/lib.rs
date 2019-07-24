#![no_std]
#![feature(alloc, cell_update)]

#[macro_use]
extern crate alloc;
extern crate cl_std;

use alloc::string::String;
use cl_std::contract_api::{
    add_uref, call_contract, get_arg, get_uref, main_purse, new_uref, read, revert,
    transfer_from_purse_to_account,
};
use cl_std::key::Key;
use cl_std::uref::URef;
use cl_std::value::account::{PublicKey, PurseId};
use cl_std::value::U512;

fn get_balance(purse_id: PurseId) -> Option<U512> {
    let mint_public_hash = get_uref("mint");
    let mint_contract_key: Key = read(mint_public_hash.to_u_ptr().unwrap_or_else(|| revert(103)));

    let mint_contract_pointer = match mint_contract_key.to_c_ptr() {
        Some(ptr) => ptr,
        None => revert(104),
    };

    let main_purse_uref: URef = purse_id.value();

    call_contract(
        mint_contract_pointer,
        &(String::from("balance"), main_purse_uref),
        &vec![main_purse_uref.into()],
    )
}

#[no_mangle]
pub extern "C" fn call() {
    let source: PurseId = main_purse();
    let destination: PublicKey = get_arg(0);
    let amount: U512 = get_arg(1);

    let transfer_result = transfer_from_purse_to_account(source, destination, amount);

    // // Assert is done here
    let final_balance = get_balance(source).unwrap_or_else(|| revert(104));

    let result = format!("{:?}", transfer_result);
    // Add new urefs
    let result_uref: Key = new_uref(result).into();
    add_uref("transfer_result", &result_uref);
    add_uref("final_balance", &new_uref(final_balance).into());
}
