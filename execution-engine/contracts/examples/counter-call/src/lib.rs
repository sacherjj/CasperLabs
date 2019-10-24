#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::vec::Vec;

use contract_ffi::contract_api::ContractRef;
use contract_ffi::contract_api::{runtime, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;

const COUNTER_KEY: &str = "counter";
const GET_METHOD: &str = "get";
const INC_METHOD: &str = "inc";

#[no_mangle]
pub extern "C" fn call() {
    let counter_uref = runtime::get_key(COUNTER_KEY).unwrap_or_revert_with(Error::GetKey);
    let contract_ref = match counter_uref {
        Key::Hash(hash) => ContractRef::Hash(hash),
        _ => runtime::revert(Error::UnexpectedKeyVariant),
    };

    {
        let args = (INC_METHOD,);
        runtime::call_contract::<_, ()>(contract_ref.clone(), &args, &Vec::new())
    }

    let _result: i32 = {
        let args = (GET_METHOD,);
        runtime::call_contract(contract_ref, &args, &Vec::new())
    };
}
