#![no_std]

extern crate alloc;

use alloc::{string::String, vec};

use contract_ffi::{
    contract_api::runtime, key::Key, unwrap_or_revert::UnwrapOrRevert, value::U512,
};

#[no_mangle]
pub extern "C" fn call() {
    let mint = Key::Hash([
        164, 102, 153, 51, 236, 214, 169, 167, 126, 44, 250, 247, 179, 214, 203, 229, 239, 69, 145,
        25, 5, 153, 113, 55, 255, 188, 176, 201, 7, 4, 42, 100,
    ])
    .to_c_ptr()
    .unwrap();
    //let x = contract_api::get_uref("mint");

    let amount1 = U512::from(100);
    let purse1: Key = runtime::call_contract(mint.clone(), &("create", amount1), &vec![])
        .to_t()
        .unwrap_or_revert();

    let amount2 = U512::from(300);
    let purse2: Key = runtime::call_contract(mint.clone(), &("create", amount2), &vec![])
        .to_t()
        .unwrap_or_revert();

    let result: String = runtime::call_contract(
        mint.clone(),
        &("transfer", purse1, purse2, U512::from(70)),
        &vec![purse1],
    )
    .to_t()
    .unwrap_or_revert();

    assert!(&result == "Success!");

    let new_amount1: Option<U512> =
        runtime::call_contract(mint.clone(), &("balance", purse1), &vec![purse1])
            .to_t()
            .unwrap_or_revert();
    let new_amount2: Option<U512> =
        runtime::call_contract(mint.clone(), &("balance", purse2), &vec![purse2])
            .to_t()
            .unwrap_or_revert();

    assert!(new_amount1.unwrap() == U512::from(30));
    assert!(new_amount2.unwrap() == U512::from(370));
}
