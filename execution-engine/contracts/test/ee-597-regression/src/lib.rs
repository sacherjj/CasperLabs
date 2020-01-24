#![no_std]

use contract::contract_api::{account, runtime, system};
use types::{account::PurseId, ContractRef, U512};

const POS_BOND: &str = "bond";

fn bond(pos: ContractRef, amount: &U512, source: PurseId) {
    runtime::call_contract::<_, ()>(pos, (POS_BOND, *amount, source));
}

#[no_mangle]
pub extern "C" fn call() {
    bond(
        system::get_proof_of_stake(),
        &U512::from(0),
        account::get_main_purse(),
    );
}
