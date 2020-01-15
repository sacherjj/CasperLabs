use engine_shared::transform::Transform;
use engine_test_support::low_level::{
    DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT, STANDARD_PAYMENT_CONTRACT,
};
use types::{account::PublicKey, Key, URef};

fn get_uref(key: Key) -> URef {
    match key {
        Key::URef(uref) => uref,
        _ => panic!("Key {:?} is not an URef", key),
    }
}

fn do_pass(pass: &str) -> (URef, URef) {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (*DEFAULT_PAYMENT,))
            .with_session_code("ee_441_rng_state.wasm", (pass.to_string(),))
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecuteRequestBuilder::from_deploy_item(deploy).build()
    };

    let transforms = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
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
