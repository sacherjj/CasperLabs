use alloc::string::String;
use alloc::string::ToString;
use alloc::vec;

use contract_ffi::contract_api::account::PublicKey;
use contract_ffi::contract_api::runtime;
use contract_ffi::contract_api::storage;
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::U512;

use erc20_logic::ERC20Trait;
use erc20_logic::ERC20TransferError;

use crate::api::Api;
use crate::error::Error;

pub const INIT_FLAG_KEY: [u8; 32] = [1u8; 32];
pub const TOTAL_SUPPLY_KEY: [u8; 32] = [255u8; 32];

struct ERC20Token;

impl ERC20Trait<U512, PublicKey> for ERC20Token {
    fn read_balance(address: &PublicKey) -> Option<U512> {
        storage::read_local(address.value()).unwrap_or_revert()
    }
    fn save_balance(address: &PublicKey, balance: U512) {
        storage::write_local(address.value(), balance);
    }
    fn read_total_supply() -> Option<U512> {
        storage::read_local(TOTAL_SUPPLY_KEY).unwrap_or_revert()
    }
    fn save_total_supply(total_supply: U512) {
        storage::write_local(TOTAL_SUPPLY_KEY, total_supply);
    }
}

fn constructor() {
    match Api::from_args() {
        Api::InitErc20(amount) => {
            ERC20Token::mint(&runtime::get_caller(), amount);
        },
        _ => runtime::revert(Error::UnknownErc20ConstructorCommand)
    }
}

fn call() {
    match Api::from_args() {
        Api::Transfer(recipient, amount) => {
            ERC20Token::transfer(&runtime::get_caller(), &recipient, amount);
            // match ERC20Token::transfer(&runtime::get_caller(), &recipient, amount) {
            //     Ok(()) => {},
            //     Err(ERC20TransferError::NotEnoughBalance) => {
            //         runtime::revert(Error::TransferFailureNotEnoughBalance)
            //     }
            // };
        },
        Api::BalanceOf(address) => {
            runtime::ret(ERC20Token::balance_of(&address), vec![]);
        },
        Api::TotalSupply => {
            runtime::ret(ERC20Token::total_supply(), vec![]);
        }
        _ => runtime::revert(Error::UnknownErc20CallCommand)
    }
}

fn is_not_initialized() -> bool {
    let flag: Option<String> = storage::read_local(INIT_FLAG_KEY).unwrap_or_revert();
    flag.is_none()
}

fn mark_as_initialized() { 
    storage::write_local(INIT_FLAG_KEY, String::from("initialized")); 
}

#[no_mangle]
pub extern "C" fn erc20() {
    if is_not_initialized() {
        constructor();
        mark_as_initialized();
    } else {
        call();
    }
}
