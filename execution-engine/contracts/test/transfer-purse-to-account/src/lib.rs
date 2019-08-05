#![no_std]
#![feature(alloc, cell_update)]

#[macro_use]
extern crate alloc;
extern crate cl_std;

use cl_std::contract_api::{
    add_uref, get_arg, get_balance, main_purse, new_uref, revert, transfer_from_purse_to_account,
};
use cl_std::key::Key;
use cl_std::value::account::{PublicKey, PurseId};
use cl_std::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    let source: PurseId = main_purse();
    let destination: PublicKey = get_arg(0);
    let amount: U512 = get_arg(1);

    let transfer_result = transfer_from_purse_to_account(source, destination, amount);

    // Assert is done here
    let final_balance = get_balance(source).unwrap_or_else(|| revert(103));

    let result = format!("{:?}", transfer_result);
    // Add new urefs
    let result_uref: Key = new_uref(result).into();
    add_uref("transfer_result", &result_uref);
    add_uref("final_balance", &new_uref(final_balance).into());
}
