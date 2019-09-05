#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api;
use contract_ffi::key::Key;
use contract_ffi::value::U512;

#[repr(u32)]
enum Error {
    PurseNotCreated = 1,
    MintNotFound = 2,
    BalanceNotFound = 3,
    BalanceMismatch = 4,
}

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = 12345.into();
    let new_purse = contract_api::mint_purse(amount)
        .unwrap_or_else(|_| contract_api::revert(Error::PurseNotCreated as u32));

    let mint = contract_api::get_mint()
        .unwrap_or_else(|| contract_api::revert(Error::MintNotFound as u32));

    let balance: Option<U512> = contract_api::call_contract(
        mint,
        &("balance", new_purse),
        &vec![Key::URef(new_purse.value())],
    );

    match balance {
        None => contract_api::revert(Error::BalanceNotFound as u32),

        Some(balance) if balance == amount => (),

        _ => contract_api::revert(Error::BalanceMismatch as u32),
    }
}
