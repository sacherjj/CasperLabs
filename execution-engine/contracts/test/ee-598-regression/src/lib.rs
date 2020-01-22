#![no_std]

use contract::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PurseId, ApiError, ContractRef, U512};

const POS_BOND: &str = "bond";
const POS_UNBOND: &str = "unbond";

fn bond(pos: ContractRef, amount: U512, source: PurseId) {
    runtime::call_contract::<_, ()>(pos, (POS_BOND, amount, source));
}

fn unbond(pos: ContractRef, amount: Option<U512>) {
    runtime::call_contract::<_, ()>(pos, (POS_UNBOND, amount));
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = system::get_proof_of_stake();
    let amount: U512 = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    bond(pos_pointer.clone(), amount, account::get_main_purse());
    unbond(pos_pointer, Some(amount + 1));
}
