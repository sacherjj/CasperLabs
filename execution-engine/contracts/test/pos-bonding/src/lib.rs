#![no_std]

extern crate alloc;

use alloc::string::String;

use contract::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    account::{PublicKey, PurseId},
    ApiError, ContractRef, U512,
};

#[repr(u16)]
enum Error {
    UnableToSeedAccount,
    UnknownCommand,
}

fn bond(pos: &ContractRef, amount: &U512, source: PurseId) {
    runtime::call_contract::<_, ()>(pos.clone(), (POS_BOND, *amount, source));
}

fn unbond(pos: &ContractRef, amount: Option<U512>) {
    runtime::call_contract::<_, ()>(pos.clone(), (POS_UNBOND, amount));
}

const POS_BOND: &str = "bond";
const POS_UNBOND: &str = "unbond";

const TEST_BOND: &str = "bond";
const TEST_BOND_FROM_MAIN_PURSE: &str = "bond-from-main-purse";
const TEST_SEED_NEW_ACCOUNT: &str = "seed_new_account";
const TEST_UNBOND: &str = "unbond";

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = system::get_proof_of_stake();

    let command: String = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    if command == TEST_BOND {
        // Creates new purse with desired amount based on main purse and sends funds

        let amount = runtime::get_arg(1)
            .unwrap_or_revert_with(ApiError::MissingArgument)
            .unwrap_or_revert_with(ApiError::InvalidArgument);
        let p1 = system::create_purse();

        system::transfer_from_purse_to_purse(account::get_main_purse(), p1, amount)
            .unwrap_or_revert();

        bond(&pos_pointer, &amount, p1);
    } else if command == TEST_BOND_FROM_MAIN_PURSE {
        let amount = runtime::get_arg(1)
            .unwrap_or_revert_with(ApiError::MissingArgument)
            .unwrap_or_revert_with(ApiError::InvalidArgument);

        bond(&pos_pointer, &amount, account::get_main_purse());
    } else if command == TEST_SEED_NEW_ACCOUNT {
        let account: PublicKey = runtime::get_arg(1)
            .unwrap_or_revert_with(ApiError::MissingArgument)
            .unwrap_or_revert_with(ApiError::InvalidArgument);
        let amount: U512 = runtime::get_arg(2)
            .unwrap_or_revert_with(ApiError::MissingArgument)
            .unwrap_or_revert_with(ApiError::InvalidArgument);
        system::transfer_from_purse_to_account(account::get_main_purse(), account, amount)
            .unwrap_or_revert_with(ApiError::User(Error::UnableToSeedAccount as u16));
    } else if command == TEST_UNBOND {
        let maybe_amount: Option<U512> = runtime::get_arg(1)
            .unwrap_or_revert_with(ApiError::MissingArgument)
            .unwrap_or_revert_with(ApiError::InvalidArgument);
        unbond(&pos_pointer, maybe_amount);
    } else {
        runtime::revert(ApiError::User(Error::UnknownCommand as u16));
    }
}
