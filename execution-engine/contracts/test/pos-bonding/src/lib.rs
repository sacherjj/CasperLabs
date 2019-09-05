#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::prelude::v1::{String, Vec};

use contract_ffi::contract_api::pointers::{ContractPointer, UPointer};
use contract_ffi::contract_api::{
    call_contract, create_purse, get_arg, get_uref, main_purse, read, revert,
    transfer_from_purse_to_account, transfer_from_purse_to_purse, PurseTransferResult,
    TransferResult,
};
use contract_ffi::key::Key;
use contract_ffi::uref::AccessRights;
use contract_ffi::value::account::{PublicKey, PurseId};
use contract_ffi::value::U512;

enum Error {
    GetPosOuterURef = 1000,
    GetPosInnerURef = 1001,
    PurseToPurseTransfer = 1002,
    UnableToSeedAccount = 1003,
    UnknownCommand = 1004,
}

fn purse_to_key(p: PurseId) -> Key {
    Key::URef(p.value())
}

fn get_pos_contract() -> ContractPointer {
    let outer: UPointer<Key> = get_uref("pos")
        .and_then(Key::to_u_ptr)
        .unwrap_or_else(|| revert(Error::GetPosInnerURef as u32));
    if let Some(ContractPointer::URef(inner)) = read::<Key>(outer).to_c_ptr() {
        ContractPointer::URef(UPointer::new(inner.0, AccessRights::READ))
    } else {
        revert(Error::GetPosOuterURef as u32)
    }
}

fn bond(pos: &ContractPointer, amount: &U512, source: PurseId) {
    call_contract::<_, ()>(
        pos.clone(),
        &(POS_BOND, *amount, source),
        &vec![purse_to_key(source)],
    );
}

fn unbond(pos: &ContractPointer, amount: Option<U512>) {
    call_contract::<_, ()>(pos.clone(), &(POS_UNBOND, amount), &Vec::<Key>::new());
}

const POS_BOND: &str = "bond";
const POS_UNBOND: &str = "unbond";

const TEST_BOND: &str = "bond";
const TEST_BOND_FROM_MAIN_PURSE: &str = "bond-from-main-purse";
const TEST_SEED_NEW_ACCOUNT: &str = "seed_new_account";
const TEST_UNBOND: &str = "unbond";

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = get_pos_contract();

    let command: String = get_arg(0);
    if command == TEST_BOND {
        // Creates new purse with desired amount based on main purse and sends funds

        let amount = get_arg(1);
        let p1 = create_purse();

        if transfer_from_purse_to_purse(main_purse(), p1, amount)
            == PurseTransferResult::TransferError
        {
            revert(Error::PurseToPurseTransfer as u32);
        }

        bond(&pos_pointer, &amount, p1);
    } else if command == TEST_BOND_FROM_MAIN_PURSE {
        let amount = get_arg(1);

        bond(&pos_pointer, &amount, main_purse());
    } else if command == TEST_SEED_NEW_ACCOUNT {
        let account: PublicKey = get_arg(1);
        let amount: U512 = get_arg(2);
        if transfer_from_purse_to_account(main_purse(), account, amount)
            == TransferResult::TransferError
        {
            revert(Error::UnableToSeedAccount as u32);
        }
    } else if command == TEST_UNBOND {
        let maybe_amount: Option<U512> = get_arg(1);
        unbond(&pos_pointer, maybe_amount);
    } else {
        revert(Error::UnknownCommand as u32);
    }
}
