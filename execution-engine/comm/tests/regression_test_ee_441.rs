extern crate casperlabs_engine_grpc_server;
extern crate common;
extern crate execution_engine;
extern crate grpc;
extern crate shared;
extern crate storage;

use std::collections::HashMap;

use common::key::Key;
use common::uref::URef;
use common::value::Value;
use shared::transform::Transform;
use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

#[allow(dead_code)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

fn get_uref(key: Key) -> URef {
    match key {
        Key::URef(uref) => uref,
        _ => panic!("Key {:?} is not an URef", key),
    }
}

fn do_pass(pass: &str) -> (URef, URef) {
    // This test runs a contract that's after every call extends the same key with more data
    let transforms = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "ee_441_rng_state.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            pass.to_string(),
        )
        .expect_success()
        .commit()
        .get_transforms();

    let transform = &transforms[0];
    let account_transform = &transform[&Key::Account(GENESIS_ADDR)];
    let account = if let Transform::Write(Value::Account(account)) = account_transform {
        account
    } else {
        panic!(
            "Transform for account is expected to be of type Write(Account) but got {:?}",
            account_transform
        );
    };

    (
        get_uref(account.urefs_lookup()["uref1"]),
        get_uref(account.urefs_lookup()["uref2"]),
    )
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

    // First urefs from each pass should yield same results where pass1 is the baseline
    assert_eq!(pass1_uref1.addr(), pass2_uref1.addr());
    assert_eq!(pass2_uref1.addr(), pass3_uref1.addr());

    // Second urefs from each pass should yield the same result where pass1 is the baseline
    assert_eq!(pass1_uref2.addr(), pass2_uref2.addr());
    assert_eq!(pass2_uref2.addr(), pass3_uref2.addr());
}
