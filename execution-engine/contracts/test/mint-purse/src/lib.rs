#![no_std]

use contract::{
    contract_api::{runtime, system, Error as ApiError},
    system_contracts::mint,
    unwrap_or_revert::UnwrapOrRevert,
    uref::URef,
    value::{account::PurseId, U512},
};

#[repr(u16)]
enum Error {
    BalanceNotFound = 0,
    BalanceMismatch,
}

fn mint_purse(amount: U512) -> Result<PurseId, mint::Error> {
    let mint = system::get_mint();

    let result: Result<URef, mint::Error> = runtime::call_contract(mint, ("mint", amount));

    result.map(PurseId::new)
}

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = 12345.into();
    let new_purse = mint_purse(amount).unwrap_or_revert();

    let mint = system::get_mint();

    let balance: Option<U512> = runtime::call_contract(mint, ("balance", new_purse));

    match balance {
        None => runtime::revert(ApiError::User(Error::BalanceNotFound as u16)),
        Some(balance) if balance == amount => (),
        _ => runtime::revert(ApiError::User(Error::BalanceMismatch as u16)),
    }
}
