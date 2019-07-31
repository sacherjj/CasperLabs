#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;

extern crate cl_std;

use cl_std::contract_api::{get_arg, revert, set_action_threshold};
use cl_std::value::account::{ActionType, Weight};

#[no_mangle]
pub extern "C" fn call() {
    let key_management_threshold: Weight = get_arg(0);
    let deployment_threshold: Weight = get_arg(1);

    set_action_threshold(ActionType::KeyManagement, key_management_threshold)
        .unwrap_or_else(|_| revert(100));
    set_action_threshold(ActionType::Deployment, deployment_threshold)
        .unwrap_or_else(|_| revert(200));
}
