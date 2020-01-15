use engine_shared::{stored_value::StoredValue, transform::Transform};
use engine_test_support::low_level::{
    exec_with_return, WasmTestBuilder, DEFAULT_ACCOUNT_ADDR, DEFAULT_BLOCK_TIME,
    DEFAULT_GENESIS_CONFIG,
};
use types::{Key, URef};

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
        Some(Transform::Write(StoredValue::Contract(_))) => (),

        _ => panic!("Expected contract to be written under the key"),
    }
}
