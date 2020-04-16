#![no_std]
#![no_main]

use contract::contract_api::{account, runtime, system};
use types::{ContractRef, URef, U512};

const POS_BOND: &str = "bond";

fn bond(pos: ContractRef, amount: &U512, source_purse: URef) {
    runtime::call_contract::<_, ()>(pos, (POS_BOND, *amount, source_purse));
}

#[no_mangle]
pub extern "C" fn call() {
    bond(
        system::get_proof_of_stake(),
        &U512::from(0),
        account::get_main_purse(),
    );
}
