#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;
use cl_std::contract_api::{get_arg, revert, set_action_threshold};
use cl_std::value::account::{ActionType, Weight};

#[no_mangle]
pub extern "C" fn call() {
    // Note: Thresholds are passed via ABI as two U8s.
    // Can we pass a weight in ABI just as U8?
    // Looks like weight in state.rs is u32
    let km_weight: u32 = get_arg(0);
    let key_management_threshold = Weight::new(km_weight as u8);
    let dp_weight: u32 = get_arg(1);
    let deploy_threshold = Weight::new(dp_weight as u8);

    if key_management_threshold != Weight::new(0) {
        set_action_threshold(ActionType::KeyManagement, key_management_threshold)
            .unwrap_or_else(|_| revert(100));
    }

    if deploy_threshold != Weight::new(0) {
        set_action_threshold(ActionType::Deployment, deploy_threshold)
            .unwrap_or_else(|_| revert(200));
    }
}
