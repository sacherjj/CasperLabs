#![no_std]

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::ApiError;

const COUNTER_KEY: &str = "counter";
const GET_METHOD: &str = "get";
const INC_METHOD: &str = "inc";

#[no_mangle]
pub extern "C" fn call() {
    let counter_uref = runtime::get_key(COUNTER_KEY).unwrap_or_revert_with(ApiError::GetKey);
    let contract_ref = counter_uref
        .to_contract_ref()
        .unwrap_or_revert_with(ApiError::UnexpectedKeyVariant);

    {
        let args = (INC_METHOD,);
        runtime::call_contract::<_, ()>(contract_ref.clone(), args);
    }

    let _result: i32 = {
        let args = (GET_METHOD,);
        runtime::call_contract(contract_ref, args)
    };
}
