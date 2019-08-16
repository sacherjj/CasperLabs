#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;
use cl_std::contract_api::{add_associated_key, get_arg, revert, set_action_threshold};
use cl_std::value::account::{ActionType, AddKeyFailure, PublicKey, Weight};

#[no_mangle]
pub extern "C" fn call() {
    match add_associated_key(PublicKey::new([123; 32]), Weight::new(100)) {
        Err(AddKeyFailure::DuplicateKey) => {}
        Err(_) => revert(50),
        Ok(_) => {}
    };

    let key_management_threshold: Weight = get_arg(0);
    let deploy_threshold: Weight = get_arg(1);
    if key_management_threshold != Weight::new(0) {
        set_action_threshold(ActionType::KeyManagement, key_management_threshold)
            .unwrap_or_else(|_| revert(100));
    }

    if deploy_threshold != Weight::new(0) {
        set_action_threshold(ActionType::Deployment, deploy_threshold)
            .unwrap_or_else(|_| revert(200));
    }
}
