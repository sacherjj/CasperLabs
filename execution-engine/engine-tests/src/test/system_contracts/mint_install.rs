use contract_ffi::{key::Key, uref::URef, value::Value};
use engine_shared::transform::Transform;

use crate::{
    support::{
        exec_with_return,
        test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME},
    },
    test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG},
};

const DEPLOY_HASH_1: [u8; 32] = [1u8; 32];

#[ignore]
#[test]
fn should_run_mint_install_contract() {
    let mut builder = WasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_GENESIS_CONFIG);

    let (ret_value, ret_urefs, effect): (URef, _, _) = exec_with_return::exec(
        &mut builder,
        DEFAULT_ACCOUNT_ADDR,
        "mint_install.wasm",
        DEFAULT_BLOCK_TIME,
        DEPLOY_HASH_1,
        (),
        vec![],
    )
    .expect("should run successfully");

    // should return a uref
    assert_eq!(ret_value, ret_urefs[0]);

    // should have written a contract under that uref
    match effect
        .transforms
        .get(&Key::URef(ret_value.remove_access_rights()))
    {
        Some(Transform::Write(Value::Contract(_))) => (),

        _ => panic!("Expected contract to be written under the key"),
    }
}
