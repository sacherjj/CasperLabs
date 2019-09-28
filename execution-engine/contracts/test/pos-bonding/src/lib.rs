#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;
use alloc::prelude::v1::{String, Vec};

use contract_ffi::contract_api::pointers::ContractPointer;
use contract_ffi::contract_api::{self, Error as ApiError, PurseTransferResult, TransferResult};
use contract_ffi::key::Key;
use contract_ffi::value::account::{PublicKey, PurseId};
use contract_ffi::value::U512;

#[repr(u16)]
enum Error {
    UnableToSeedAccount,
    UnknownCommand,
}

fn purse_to_key(p: PurseId) -> Key {
    Key::URef(p.value())
}

fn bond(pos: &ContractPointer, amount: &U512, source: PurseId) {
    contract_api::call_contract::<_, ()>(
        pos.clone(),
        &(POS_BOND, *amount, source),
        &vec![purse_to_key(source)],
    );
}

fn unbond(pos: &ContractPointer, amount: Option<U512>) {
    contract_api::call_contract::<_, ()>(pos.clone(), &(POS_UNBOND, amount), &Vec::<Key>::new());
}

const POS_BOND: &str = "bond";
const POS_UNBOND: &str = "unbond";

const TEST_BOND: &str = "bond";
const TEST_BOND_FROM_MAIN_PURSE: &str = "bond-from-main-purse";
const TEST_SEED_NEW_ACCOUNT: &str = "seed_new_account";
const TEST_UNBOND: &str = "unbond";

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = contract_api::get_pos();

    let command: String = match contract_api::get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(ApiError::InvalidArgument.into()),
        None => contract_api::revert(ApiError::MissingArgument.into()),
    };
    if command == TEST_BOND {
        // Creates new purse with desired amount based on main purse and sends funds

        let amount = match contract_api::get_arg(1) {
            Some(Ok(data)) => data,
            Some(Err(_)) => contract_api::revert(ApiError::InvalidArgument.into()),
            None => contract_api::revert(ApiError::MissingArgument.into()),
        };
        let p1 = contract_api::create_purse();

        if contract_api::transfer_from_purse_to_purse(contract_api::main_purse(), p1, amount)
            == PurseTransferResult::TransferError
        {
            contract_api::revert(ApiError::Transfer.into());
        }

        bond(&pos_pointer, &amount, p1);
    } else if command == TEST_BOND_FROM_MAIN_PURSE {
        let amount = match contract_api::get_arg(1) {
            Some(Ok(data)) => data,
            Some(Err(_)) => contract_api::revert(ApiError::InvalidArgument.into()),
            None => contract_api::revert(ApiError::MissingArgument.into()),
        };

        bond(&pos_pointer, &amount, contract_api::main_purse());
    } else if command == TEST_SEED_NEW_ACCOUNT {
        let account: PublicKey = match contract_api::get_arg(1) {
            Some(Ok(data)) => data,
            Some(Err(_)) => contract_api::revert(ApiError::InvalidArgument.into()),
            None => contract_api::revert(ApiError::MissingArgument.into()),
        };
        let amount: U512 = match contract_api::get_arg(2) {
            Some(Ok(data)) => data,
            Some(Err(_)) => contract_api::revert(ApiError::InvalidArgument.into()),
            None => contract_api::revert(ApiError::MissingArgument.into()),
        };
        if contract_api::transfer_from_purse_to_account(contract_api::main_purse(), account, amount)
            == TransferResult::TransferError
        {
            contract_api::revert(ApiError::User(Error::UnableToSeedAccount as u16).into());
        }
    } else if command == TEST_UNBOND {
        let maybe_amount: Option<U512> = match contract_api::get_arg(1) {
            Some(Ok(data)) => data,
            Some(Err(_)) => contract_api::revert(ApiError::InvalidArgument.into()),
            None => contract_api::revert(ApiError::MissingArgument.into()),
        };
        unbond(&pos_pointer, maybe_amount);
    } else {
        contract_api::revert(ApiError::User(Error::UnknownCommand as u16).into());
    }
}
