#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{runtime, system, Error as ApiError};
use contract_ffi::key::Key;
use contract_ffi::system_contracts::mint;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::uref::URef;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

#[repr(u16)]
enum Error {
    BalanceNotFound = 0,
    BalanceMismatch,
}

fn mint_purse(amount: U512) -> Result<PurseId, mint::Error> {
    let mint = system::get_mint();

    let result: Result<URef, mint::Error> =
        runtime::call_contract(mint, &("mint", amount), &vec![]);

    result.map(PurseId::new)
}

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = 12345.into();
    let new_purse = mint_purse(amount).unwrap_or_revert();

    let mint = system::get_mint();

    let balance: Option<U512> = runtime::call_contract(
        mint,
        &("balance", new_purse),
        &vec![Key::URef(new_purse.value())],
    );

    match balance {
        None => runtime::revert(ApiError::User(Error::BalanceNotFound as u16)),
        Some(balance) if balance == amount => (),
        _ => runtime::revert(ApiError::User(Error::BalanceMismatch as u16)),
    }
}
