#![no_std]

extern crate alloc;

use alloc::vec;

use contract_ffi::{
    contract_api::{account, runtime, system, ContractRef, Error},
    key::Key,
    unwrap_or_revert::UnwrapOrRevert,
    value::{account::PurseId, U512},
};

fn purse_to_key(p: PurseId) -> Key {
    Key::URef(p.value())
}

const POS_BOND: &str = "bond";
const POS_UNBOND: &str = "unbond";

fn bond(pos: ContractRef, amount: U512, source: PurseId) {
    runtime::call_contract::<_, ()>(pos, (POS_BOND, amount, source), vec![purse_to_key(source)]);
}

fn unbond(pos: ContractRef, amount: Option<U512>) {
    runtime::call_contract::<_, ()>(pos, (POS_UNBOND, amount), vec![]);
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = system::get_proof_of_stake();
    let amount: U512 = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    bond(pos_pointer.clone(), amount, account::get_main_purse());
    unbond(pos_pointer, Some(amount + 1));
}
