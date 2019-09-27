use contract_ffi::key::Key;
use contract_ffi::uref::URef;
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;
use engine_shared::transform::Transform;

use crate::support::test_support::{
    InMemoryWasmTestBuilder, DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

fn get_uref(key: Key) -> URef {
    match key {
        Key::URef(uref) => uref,
        _ => panic!("Key {:?} is not an URef", key),
    }
}

fn do_pass(pass: &str) -> (URef, URef) {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let transforms = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_args(
            DEFAULT_ACCOUNT_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "ee_441_rng_state.wasm",
            (pass.to_string(),),
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit()
        .get_transforms();

    let transform = &transforms[0];
    let account_transform = &transform[&Key::Account(DEFAULT_ACCOUNT_ADDR)];
    let keys = if let Transform::AddKeys(keys) = account_transform {
        keys
    } else {
        panic!(
            "Transform for account is expected to be of type AddKeys(keys) but got {:?}",
            account_transform
        );
    };

    (get_uref(keys["uref1"]), get_uref(keys["uref2"]))
}

#[ignore]
#[test]
fn should_properly_pass_rng_state_to_subcontracts() {
    // the baseline pass, no subcalls
    let (pass1_uref1, pass1_uref2) = do_pass("pass1");
    // second pass does a subcall that does nothing, should be consistent with pass1
    let (pass2_uref1, pass2_uref2) = do_pass("pass2");
    // second pass calls new_uref, and uref2 is returned from a sub call
    let (pass3_uref1, pass3_uref2) = do_pass("pass3");

    // First urefs from each pass should yield same results where pass1 is the
    // baseline
    assert_eq!(pass1_uref1.addr(), pass2_uref1.addr());
    assert_eq!(pass2_uref1.addr(), pass3_uref1.addr());

    // Second urefs from each pass should yield the same result where pass1 is the
    // baseline
    assert_eq!(pass1_uref2.addr(), pass2_uref2.addr());
    assert_eq!(pass2_uref2.addr(), pass3_uref2.addr());
}
