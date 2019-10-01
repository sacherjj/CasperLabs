use contract_ffi::key::Key;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::{ProtocolVersion, Value, U512};
use engine_grpc_server::engine_server::ipc::DeployCode;
use engine_shared::transform::Transform;
use engine_wasm_prep::wasm_costs::WasmCosts;

use crate::support::test_support::{
    self, DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, UpgradeRequestBuilder,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG, DEFAULT_WASM_COSTS};

const PROTOCOL_VERSION: u64 = 1;
const NEW_PROTOCOL_VERSION: u64 = 2;
const DEFAULT_ACTIVATION_POINT: u64 = 1;
const STANDARD_PAYMENT_WASM: &str = "standard_payment.wasm";
const MODIFIED_MINT_UPGRADER_CONTRACT_NAME: &str = "modified_mint_upgrader.wasm";
const MODIFIED_MINT_CALLER_CONTRACT_NAME: &str = "modified_mint_caller.wasm";
const PAYMENT_AMOUNT: u64 = 200_000_000;

fn get_upgraded_wasm_costs() -> WasmCosts {
    WasmCosts {
        regular: 1,
        div: 1,
        mul: 1,
        mem: 1,
        initial_mem: 4096,
        grow_mem: 8192,
        memcpy: 1,
        max_stack_height: 64 * 1024,
        opcodes_mul: 3,
        opcodes_div: 8,
    }
}

#[ignore]
#[test]
fn should_upgrade_only_protocol_version() {
    let account_1_public_key = PublicKey::new(DEFAULT_ACCOUNT_ADDR);

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    let mut upgrade_request = {
        UpgradeRequestBuilder::new()
            .with_current_protocol_version(PROTOCOL_VERSION)
            .with_new_protocol_version(NEW_PROTOCOL_VERSION)
            .with_activation_point(DEFAULT_ACTIVATION_POINT)
            .build()
    };

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(upgrade_response.has_success(), "expected success");

    let exec_request_call = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code(MODIFIED_MINT_CALLER_CONTRACT_NAME, ())
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(PAYMENT_AMOUNT),))
            .with_authorization_keys(&[account_1_public_key])
            .build();

        ExecuteRequestBuilder::new()
            .push_deploy(deploy)
            .with_protocol_version(NEW_PROTOCOL_VERSION)
            .build()
    };

    builder
        .exec_with_exec_request(exec_request_call)
        .expect_success()
        .commit();

    let upgraded_wasm_costs = builder
        .get_engine_state()
        .wasm_costs(ProtocolVersion::new(NEW_PROTOCOL_VERSION))
        .expect("should have costs");

    assert_eq!(
        *DEFAULT_WASM_COSTS, upgraded_wasm_costs,
        "upgraded costs should equal original costs"
    );
}

#[ignore]
#[test]
fn should_upgrade_system_contract() {
    let account_1_public_key = PublicKey::new(DEFAULT_ACCOUNT_ADDR);

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    let mut upgrade_request = {
        let bytes = test_support::read_wasm_file_bytes(MODIFIED_MINT_UPGRADER_CONTRACT_NAME);
        let mut installer_code = DeployCode::new();
        installer_code.set_code(bytes);
        UpgradeRequestBuilder::new()
            .with_current_protocol_version(PROTOCOL_VERSION)
            .with_new_protocol_version(NEW_PROTOCOL_VERSION)
            .with_activation_point(DEFAULT_ACTIVATION_POINT)
            .with_installer_code(installer_code)
            .build()
    };

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(upgrade_response.has_success(), "expected success");

    let exec_request_call = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code(MODIFIED_MINT_CALLER_CONTRACT_NAME, ())
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(PAYMENT_AMOUNT),))
            .with_authorization_keys(&[account_1_public_key])
            .build();

        ExecuteRequestBuilder::new()
            .push_deploy(deploy)
            .with_protocol_version(NEW_PROTOCOL_VERSION)
            .build()
    };

    builder.exec_with_exec_request(exec_request_call);

    let transforms = builder.get_transforms();
    let transform = &transforms[0];

    let new_keys = if let Some(Transform::AddKeys(keys)) =
        transform.get(&Key::Account(DEFAULT_ACCOUNT_ADDR))
    {
        keys
    } else {
        panic!(
            "expected AddKeys transform for given key but received {:?}",
            transforms[0]
        );
    };

    let version_uref = new_keys
        .get("output_version")
        .expect("version_uref should exist");

    builder.commit();

    let version_value: Value = builder
        .query(None, *version_uref, &[])
        .expect("should find version_uref value");

    assert_eq!(
        version_value,
        Value::String("1.1.0".to_string()),
        "expected new version endpoint output"
    );
}

#[ignore]
#[test]
fn should_upgrade_wasm_costs() {
    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    let new_costs = get_upgraded_wasm_costs();

    let mut upgrade_request = {
        UpgradeRequestBuilder::new()
            .with_current_protocol_version(PROTOCOL_VERSION)
            .with_new_protocol_version(NEW_PROTOCOL_VERSION)
            .with_activation_point(DEFAULT_ACTIVATION_POINT)
            .with_new_costs(new_costs)
            .build()
    };

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(upgrade_response.has_success(), "expected success");

    let upgraded_wasm_costs = builder
        .get_engine_state()
        .wasm_costs(ProtocolVersion::new(NEW_PROTOCOL_VERSION))
        .expect("should have upgraded costs");

    assert_eq!(
        new_costs, upgraded_wasm_costs,
        "upgraded costs should equal new costs"
    );
}

#[ignore]
#[test]
fn should_upgrade_system_contract_and_wasm_costs() {
    let account_1_public_key = PublicKey::new(DEFAULT_ACCOUNT_ADDR);

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    let new_costs = get_upgraded_wasm_costs();

    let mut upgrade_request = {
        let bytes = test_support::read_wasm_file_bytes(MODIFIED_MINT_UPGRADER_CONTRACT_NAME);
        let mut installer_code = DeployCode::new();
        installer_code.set_code(bytes);
        UpgradeRequestBuilder::new()
            .with_current_protocol_version(PROTOCOL_VERSION)
            .with_new_protocol_version(NEW_PROTOCOL_VERSION)
            .with_activation_point(DEFAULT_ACTIVATION_POINT)
            .with_installer_code(installer_code)
            .with_new_costs(new_costs)
            .build()
    };

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(upgrade_response.has_success(), "expected success");

    let exec_request_call = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code(MODIFIED_MINT_CALLER_CONTRACT_NAME, ())
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(PAYMENT_AMOUNT),))
            .with_authorization_keys(&[account_1_public_key])
            .build();

        ExecuteRequestBuilder::new()
            .push_deploy(deploy)
            .with_protocol_version(NEW_PROTOCOL_VERSION)
            .build()
    };

    builder.exec_with_exec_request(exec_request_call);

    let transforms = builder.get_transforms();
    let transform = &transforms[0];

    let new_keys = if let Some(Transform::AddKeys(keys)) =
        transform.get(&Key::Account(DEFAULT_ACCOUNT_ADDR))
    {
        keys
    } else {
        panic!(
            "expected AddKeys transform for given key but received {:?}",
            transforms[0]
        );
    };

    let version_uref = new_keys
        .get("output_version")
        .expect("version_uref should exist");

    builder.commit();

    let version_value: Value = builder
        .query(None, *version_uref, &[])
        .expect("should find version_uref value");

    assert_eq!(
        version_value,
        Value::String("1.1.0".to_string()),
        "expected new version endpoint output"
    );

    let upgraded_wasm_costs = builder
        .get_engine_state()
        .wasm_costs(ProtocolVersion::new(NEW_PROTOCOL_VERSION))
        .expect("should have upgraded costs");

    assert_eq!(
        new_costs, upgraded_wasm_costs,
        "upgraded costs should equal new costs"
    );
}

#[ignore]
#[test]
fn should_not_downgrade() {
    let account_1_public_key = PublicKey::new(DEFAULT_ACCOUNT_ADDR);

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    let mut upgrade_request = {
        UpgradeRequestBuilder::new()
            .with_current_protocol_version(PROTOCOL_VERSION)
            .with_new_protocol_version(NEW_PROTOCOL_VERSION)
            .with_activation_point(DEFAULT_ACTIVATION_POINT)
            .build()
    };

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(upgrade_response.has_success(), "expected success");

    let exec_request_call = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code(MODIFIED_MINT_CALLER_CONTRACT_NAME, ())
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(PAYMENT_AMOUNT),))
            .with_authorization_keys(&[account_1_public_key])
            .build();

        ExecuteRequestBuilder::new()
            .push_deploy(deploy)
            .with_protocol_version(NEW_PROTOCOL_VERSION)
            .build()
    };

    builder
        .exec_with_exec_request(exec_request_call)
        .expect_success()
        .commit();

    let mut downgrade_request = {
        UpgradeRequestBuilder::new()
            .with_current_protocol_version(NEW_PROTOCOL_VERSION)
            .with_new_protocol_version(PROTOCOL_VERSION)
            .with_activation_point(DEFAULT_ACTIVATION_POINT)
            .build()
    };

    builder.upgrade_with_upgrade_request(&mut downgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(1)
        .expect("should have response");

    assert!(!upgrade_response.has_success(), "expected failure");
}

#[ignore]
#[test]
fn should_not_skip_major_versions() {
    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    // TODO: when protocol version switches to SemVer, this corresponds to major version
    let invalid_version = PROTOCOL_VERSION + 2;

    let mut upgrade_request = {
        UpgradeRequestBuilder::new()
            .with_current_protocol_version(PROTOCOL_VERSION)
            .with_new_protocol_version(invalid_version)
            .with_activation_point(DEFAULT_ACTIVATION_POINT)
            .build()
    };

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(!upgrade_response.has_success(), "expected failure");
}
