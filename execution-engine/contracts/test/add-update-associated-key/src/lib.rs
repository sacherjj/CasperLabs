#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api;
use cl_std::value::account::{PublicKey, Weight};

const INIT_WEIGHT: u8 = 1;
const MOD_WEIGHT: u8 = 2;

const ADD_FAILURE: u32 = 1;
const UPDATE_FAILURE: u32 = 2;

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = contract_api::get_arg(0);

    let weight1 = Weight::new(INIT_WEIGHT);
    contract_api::add_associated_key(account, weight1)
        .unwrap_or_else(|_| contract_api::revert(ADD_FAILURE));

    let weight2 = Weight::new(MOD_WEIGHT);
    contract_api::update_associated_key(account, weight2)
        .unwrap_or_else(|_| contract_api::revert(UPDATE_FAILURE));
}
