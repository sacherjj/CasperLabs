use contract_ffi::key::Key;
use contract_ffi::value::{ProtocolVersion, Value, U512};
use engine_core::engine_state::upgrade::ActivationPoint;
use engine_grpc_server::engine_server::ipc::DeployCode;
use engine_shared::transform::Transform;
use engine_wasm_prep::wasm_costs::WasmCosts;

use crate::support::test_support::{
    self, ExecuteRequestBuilder, InMemoryWasmTestBuilder, UpgradeRequestBuilder,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG, DEFAULT_WASM_COSTS};

const PROTOCOL_VERSION: ProtocolVersion = ProtocolVersion::V1_0_0;
const DEFAULT_ACTIVATION_POINT: ActivationPoint = 1;
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
    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    let new_protocol_version = ProtocolVersion::from_parts(2, 0, 0);

    let mut upgrade_request = {
        UpgradeRequestBuilder::new()
            .with_current_protocol_version(PROTOCOL_VERSION)
            .with_new_protocol_version(new_protocol_version)
            .with_activation_point(DEFAULT_ACTIVATION_POINT)
            .build()
    };

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(upgrade_response.has_success(), "expected success");

    let upgraded_wasm_costs = builder
        .get_engine_state()
        .wasm_costs(new_protocol_version)
        .expect("should have result")
        .expect("should have costs");

    assert_eq!(
        *DEFAULT_WASM_COSTS, upgraded_wasm_costs,
        "upgraded costs should equal original costs"
    );
}

#[ignore]
#[test]
fn should_upgrade_system_contract() {
    let mut builder = InMemoryWasmTestBuilder::default();

    let new_protocol_version = ProtocolVersion::from_parts(2, 0, 0);

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    let mut upgrade_request = {
        let bytes = test_support::read_wasm_file_bytes(MODIFIED_MINT_UPGRADER_CONTRACT_NAME);
        let mut installer_code = DeployCode::new();
        installer_code.set_code(bytes);
        UpgradeRequestBuilder::new()
            .with_current_protocol_version(PROTOCOL_VERSION)
            .with_new_protocol_version(new_protocol_version)
            .with_activation_point(DEFAULT_ACTIVATION_POINT)
            .with_installer_code(installer_code)
            .build()
    };

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(
        upgrade_response.has_success(),
        "upgrade_response expected success"
    );

    let exec_request = {
        ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            &MODIFIED_MINT_CALLER_CONTRACT_NAME,
            (U512::from(PAYMENT_AMOUNT),),
        )
        .with_protocol_version(new_protocol_version)
        .build()
    };

    builder.exec(exec_request).expect_success();

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

    let new_protocol_version = ProtocolVersion::from_parts(2, 0, 0);

    let new_costs = get_upgraded_wasm_costs();

    let mut upgrade_request = {
        UpgradeRequestBuilder::new()
            .with_current_protocol_version(PROTOCOL_VERSION)
            .with_new_protocol_version(new_protocol_version)
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
        .wasm_costs(new_protocol_version)
        .expect("should have result")
        .expect("should have upgraded costs");

    assert_eq!(
        new_costs, upgraded_wasm_costs,
        "upgraded costs should equal new costs"
    );
}

#[ignore]
#[test]
fn should_upgrade_system_contract_and_wasm_costs() {
    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    let new_protocol_version = ProtocolVersion::from_parts(2, 0, 0);

    let new_costs = get_upgraded_wasm_costs();

    let mut upgrade_request = {
        let bytes = test_support::read_wasm_file_bytes(MODIFIED_MINT_UPGRADER_CONTRACT_NAME);
        let mut installer_code = DeployCode::new();
        installer_code.set_code(bytes);
        UpgradeRequestBuilder::new()
            .with_current_protocol_version(PROTOCOL_VERSION)
            .with_new_protocol_version(new_protocol_version)
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

    let exec_request = {
        ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            &MODIFIED_MINT_CALLER_CONTRACT_NAME,
            (U512::from(PAYMENT_AMOUNT),),
        )
        .with_protocol_version(new_protocol_version)
        .build()
    };

    builder.exec(exec_request).expect_success();

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
        .wasm_costs(new_protocol_version)
        .expect("should have result")
        .expect("should have upgraded costs");

    assert_eq!(
        new_costs, upgraded_wasm_costs,
        "upgraded costs should equal new costs"
    );
}

#[ignore]
#[test]
fn should_not_downgrade() {
    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    let new_protocol_version = ProtocolVersion::from_parts(2, 0, 0);

    let mut upgrade_request = {
        UpgradeRequestBuilder::new()
            .with_current_protocol_version(PROTOCOL_VERSION)
            .with_new_protocol_version(new_protocol_version)
            .with_activation_point(DEFAULT_ACTIVATION_POINT)
            .build()
    };

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(upgrade_response.has_success(), "expected success");

    let upgraded_wasm_costs = builder
        .get_engine_state()
        .wasm_costs(new_protocol_version)
        .expect("should have result")
        .expect("should have costs");

    assert_eq!(
        *DEFAULT_WASM_COSTS, upgraded_wasm_costs,
        "upgraded costs should equal original costs"
    );

    let mut downgrade_request = {
        UpgradeRequestBuilder::new()
            .with_current_protocol_version(new_protocol_version)
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

    let sem_ver = PROTOCOL_VERSION.value();

    let invalid_version =
        ProtocolVersion::from_parts(sem_ver.major + 2, sem_ver.minor, sem_ver.patch);

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
