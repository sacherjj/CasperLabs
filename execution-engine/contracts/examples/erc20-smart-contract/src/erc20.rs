use alloc::vec::Vec;

use contract_ffi::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
    value::{account::PublicKey, CLValue, U512},
};

use erc20_logic::{ERC20Trait, ERC20TransferError, ERC20TransferFromError};

use crate::{api::Api, error::Error};

pub const INIT_FLAG_KEY: [u8; 32] = [1u8; 32];
pub const TOTAL_SUPPLY_KEY: [u8; 32] = [255u8; 32];
pub const BALANCE_BYTE: u8 = 1;

struct ERC20Token;

impl ERC20Trait<U512, PublicKey> for ERC20Token {
    fn read_balance(&mut self, address: &PublicKey) -> Option<U512> {
        let key = balance_key(address);
        storage::read_local(&key).unwrap_or_revert()
    }

    fn save_balance(&mut self, address: &PublicKey, balance: U512) {
        let key = balance_key(address);
        storage::write_local(key, balance);
    }

    fn read_total_supply(&mut self) -> Option<U512> {
        storage::read_local(&TOTAL_SUPPLY_KEY).unwrap_or_revert()
    }

    fn save_total_supply(&mut self, total_supply: U512) {
        storage::write_local(TOTAL_SUPPLY_KEY, total_supply);
    }

    fn read_allowance(&mut self, owner: &PublicKey, spender: &PublicKey) -> Option<U512> {
        let key = allowance_key(owner, spender);
        storage::read_local(&key).unwrap_or_revert()
    }

    fn save_allowance(&mut self, owner: &PublicKey, spender: &PublicKey, amount: U512) {
        let key = allowance_key(owner, spender);
        storage::write_local(key, amount);
    }
}

fn constructor() {
    let mut token = ERC20Token;
    match Api::from_args() {
        Api::InitErc20(amount) => {
            token.mint(&runtime::get_caller(), amount);
        }
        _ => runtime::revert(Error::UnknownErc20ConstructorCommand),
    }
}

fn entry_point() {
    let mut token = ERC20Token;
    match Api::from_args() {
        Api::Transfer(recipient, amount) => {
            match token.transfer(&runtime::get_caller(), &recipient, amount) {
                Ok(()) => {}
                Err(ERC20TransferError::NotEnoughBalance) => {
                    runtime::revert(Error::TransferFailureNotEnoughBalance)
                }
            };
        }
        Api::TransferFrom(owner, recipient, amount) => {
            match token.transfer_from(&runtime::get_caller(), &owner, &recipient, amount) {
                Ok(()) => {}
                Err(ERC20TransferFromError::TransferError(
                    ERC20TransferError::NotEnoughBalance,
                )) => runtime::revert(Error::TransferFromFailureNotEnoughBalance),
                Err(ERC20TransferFromError::NotEnoughAllowance) => {
                    runtime::revert(Error::TransferFromFailureNotEnoughAllowance)
                }
            };
        }
        Api::Approve(spender, amount) => token.approve(&runtime::get_caller(), &spender, amount),
        Api::BalanceOf(address) => runtime::ret(
            CLValue::from_t(token.balance_of(&address)).unwrap_or_revert(),
            Vec::new(),
        ),
        Api::TotalSupply => runtime::ret(
            CLValue::from_t(token.total_supply()).unwrap_or_revert(),
            Vec::new(),
        ),
        Api::Allowance(owner, spender) => runtime::ret(
            CLValue::from_t(token.allowance(&owner, &spender)).unwrap_or_revert(),
            Vec::new(),
        ),
        _ => runtime::revert(Error::UnknownErc20CallCommand),
    }
}

fn is_not_initialized() -> bool {
    let flag: Option<i32> = storage::read_local(&INIT_FLAG_KEY).unwrap_or_revert();
    flag.is_none()
}

fn mark_as_initialized() {
    storage::write_local(INIT_FLAG_KEY, 1);
}

fn balance_key(public_key: &PublicKey) -> Vec<u8> {
    let len = public_key.value().len() + 1;
    let mut result: Vec<u8> = Vec::with_capacity(len);
    result.extend(&[BALANCE_BYTE]);
    result.extend(&public_key.value());
    result
}

fn allowance_key(owner: &PublicKey, spender: &PublicKey) -> Vec<u8> {
    let len = owner.value().len() + spender.value().len();
    let mut result: Vec<u8> = Vec::with_capacity(len);
    result.extend(&owner.value());
    result.extend(&spender.value());
    result
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
