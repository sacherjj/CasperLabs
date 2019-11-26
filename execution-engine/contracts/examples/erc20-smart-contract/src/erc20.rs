use alloc::vec;

use contract_ffi::contract_api::account::PublicKey;
use contract_ffi::contract_api::runtime;
use contract_ffi::contract_api::storage;
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::U512;

use erc20_logic::ERC20Trait;
use erc20_logic::ERC20TransferError;
use erc20_logic::ERC20TransferFromError;

use crate::api::Api;
use crate::error::Error;

pub const INIT_FLAG_KEY: [u8; 32] = [1u8; 32];
pub const TOTAL_SUPPLY_KEY: [u8; 32] = [255u8; 32];
pub const BALANCE_SEED: [u8; 32] = [1u8; 32];

struct ERC20Token;

impl ERC20Trait<U512, PublicKey> for ERC20Token {
    fn read_balance(address: &PublicKey) -> Option<U512> {
        let key = Key::local(BALANCE_SEED, &address.value());
        storage::read_local(key).unwrap_or_revert()
    }
 
    fn save_balance(address: &PublicKey, balance: U512) {
        let key = Key::local(BALANCE_SEED, &address.value());
        storage::write_local(key, balance);
    }
 
    fn read_total_supply() -> Option<U512> {
        let key = Key::local(TOTAL_SUPPLY_KEY, &TOTAL_SUPPLY_KEY);
        storage::read_local(key).unwrap_or_revert()
    }
 
    fn save_total_supply(total_supply: U512) {
        let key = Key::local(TOTAL_SUPPLY_KEY, &TOTAL_SUPPLY_KEY);
        storage::write_local(key, total_supply);
    }
    
    fn read_allowance(owner: &PublicKey, spender: &PublicKey) -> Option<U512> {
        let key = Key::local(owner.value(), &spender.value());
        storage::read_local(key).unwrap_or_revert()
    }
 
    fn save_allowance(owner: &PublicKey, spender: &PublicKey, amount: U512) {
        let key = Key::local(owner.value(), &spender.value());
        storage::write_local(key, amount);
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

fn entry_point() {
    match Api::from_args() {
        Api::Transfer(recipient, amount) => {
            match ERC20Token::transfer(&runtime::get_caller(), &recipient, amount) {
                Ok(()) => {},
                Err(ERC20TransferError::NotEnoughBalance) => {
                    runtime::revert(Error::TransferFailureNotEnoughBalance)
                }
            };
        },
        Api::TransferFrom(owner, recipient, amount) => {
            match ERC20Token::transfer_from(&runtime::get_caller(), &owner, &recipient, amount) {
                Ok(()) => {},
                Err(ERC20TransferFromError::NotEnoughBalance) => {
                    runtime::revert(Error::TransferFromFailureNotEnoughBalance)
                },
                Err(ERC20TransferFromError::NotEnoughAllowance) => {
                    runtime::revert(Error::TransferFromFailureNotEnoughAllowance)
                }
            };
        },
        Api::Approve(spender, amount) => {
            ERC20Token::approve(&runtime::get_caller(), &spender, amount);
        },
        Api::BalanceOf(address) => {
            runtime::ret(ERC20Token::balance_of(&address), vec![]);
        },
        Api::TotalSupply => {
            runtime::ret(ERC20Token::total_supply(), vec![]);
        }
        Api::Allowance(owner, spender) => {
            runtime::ret(ERC20Token::allowance(&owner, &spender), vec![]);
        }
        _ => runtime::revert(Error::UnknownErc20CallCommand)
    }
}

fn is_not_initialized() -> bool {
    let flag: Option<i32> = storage::read_local(INIT_FLAG_KEY).unwrap_or_revert();
    flag.is_none()
}

fn mark_as_initialized() { 
    storage::write_local(INIT_FLAG_KEY, 1); 
}

#[no_mangle]
pub extern "C" fn erc20() {
    if is_not_initialized() {
        constructor();
        mark_as_initialized();
    } else {
        entry_point();
    }
}
