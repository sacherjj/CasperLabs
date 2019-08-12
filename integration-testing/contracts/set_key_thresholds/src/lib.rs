#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;
use cl_std::contract_api::{get_arg, revert, set_action_threshold};
use cl_std::value::account::{ActionType, Weight};

#[no_mangle]
pub extern "C" fn call() {
    let km_weight: u32 = get_arg(0);
    let key_management_threshold = Weight::new(km_weight as u8);
    let dep_weight: u32 = get_arg(1);
    let deploy_threshold = Weight::new(dep_weight as u8);

    if key_management_threshold != Weight::new(0) {
        set_action_threshold(ActionType::KeyManagement, key_management_threshold)
            .unwrap_or_else(|_| revert(100));
    }

    if deploy_threshold != Weight::new(0) {
        set_action_threshold(ActionType::Deployment, deploy_threshold)
            .unwrap_or_else(|_| revert(200));
    }
}
