#![no_std]
#![feature(alloc, cell_update)]

#[macro_use]
extern crate alloc;
extern crate cl_std;

use alloc::string::String;
use cl_std::contract_api::{
    add_uref, call_contract, create_purse, get_arg, get_uref, has_uref, main_purse, new_uref, read,
    revert, transfer_from_purse_to_purse,
};
use cl_std::key::Key;
use cl_std::uref::URef;
use cl_std::value::account::PurseId;
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
    let main_purse = main_purse();
    // add or update `main_purse` if it doesn't exist already
    add_uref("purse:main", &Key::from(main_purse.value()));

    let src_purse_name: String = get_arg(0);
    let src_purse = match get_uref(&src_purse_name).as_uref() {
        Some(uref) => PurseId::new(*uref),
        None => revert(101),
    };
    //    let initial_balance = get_balance(src_purse).unwrap_or_else(||revert(100));

    let dst_purse_name: String = get_arg(1);

    let dst_purse = if !has_uref(&dst_purse_name) {
        // If `dst_purse_name` is not in known urefs list then create a new purse
        let purse = create_purse();
        // and save it in known urefs
        add_uref(&dst_purse_name, &purse.value().into());
        purse
    } else {
        let uref_key = get_uref(&dst_purse_name);
        match uref_key.as_uref() {
            Some(uref) => PurseId::new(*uref),
            None => revert(102),
        }
    };
    let amount: U512 = get_arg(2);

    let transfer_result = transfer_from_purse_to_purse(src_purse, dst_purse, amount);

    // Assert is done here
    let final_balance = get_balance(main_purse).unwrap_or_else(|| revert(104));

    let result = format!("{:?}", transfer_result);
    // Add new urefs
    let result_uref: Key = new_uref(result).into();
    add_uref("purse_transfer_result", &result_uref);
    add_uref("main_purse_balance", &new_uref(final_balance).into());
}
