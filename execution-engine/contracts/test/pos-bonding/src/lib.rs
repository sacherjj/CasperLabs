#![no_std]
#![feature(alloc)]

#[macro_use]
extern crate alloc;
extern crate cl_std;

use alloc::prelude::*;

use cl_std::contract_api::pointers::{ContractPointer, UPointer};
use cl_std::contract_api::{
    call_contract, create_purse, get_arg, get_uref, main_purse, read, revert,
    transfer_from_purse_to_account, transfer_from_purse_to_purse, PurseTransferResult,
    TransferResult,
};
use cl_std::key::Key;
use cl_std::uref::AccessRights;
use cl_std::value::account::{PublicKey, PurseId};
use cl_std::value::U512;

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
        &("bond", *amount, source),
        &vec![purse_to_key(source)],
    );
}

fn unbond(pos: &ContractPointer, amount: Option<U512>) {
    call_contract::<_, ()>(pos.clone(), &("unbond", amount), &Vec::<Key>::new());
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = get_pos_contract();

    let command: String = get_arg(0);

    if command == "bond" {
        // Creates new purse with desired amount based on main purse and sends funds

        let amount = get_arg(1);
        let p1 = create_purse();

        if transfer_from_purse_to_purse(main_purse(), p1, amount)
            == PurseTransferResult::TransferError
        {
            revert(Error::PurseToPurseTransfer as u32);
        }

        bond(&pos_pointer, &amount, p1);
    } else if command == "seed_new_account" {
        let account: PublicKey = get_arg(1);
        let amount: U512 = get_arg(2);
        if transfer_from_purse_to_account(main_purse(), account, amount)
            == TransferResult::TransferError
        {
            revert(Error::UnableToSeedAccount as u32);
        }
    } else if command == "unbond" {
        let maybe_amount: Option<U512> = get_arg(1);
        unbond(&pos_pointer, maybe_amount);
    } else {
        revert(Error::UnknownCommand as u32);
    }
}
