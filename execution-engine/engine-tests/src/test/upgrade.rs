use engine_core::engine_state::{EngineConfig, EngineState};

use crate::support::test_support::{
    create_genesis_config, DeployBuilder, ExecRequestBuilder, InMemoryWasmTestBuilder,
    WasmTestBuilder,
};

use contract_ffi::key::Key;
use contract_ffi::uref::URef;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::{Contract, Value, U512};
use engine_core::engine_state::genesis::GenesisAccount;
use engine_core::execution;
use engine_grpc_server::engine_server::ipc::ExecResponse;
use engine_grpc_server::engine_server::ipc_grpc::ExecutionEngineService;
use engine_shared::motes::Motes;
use engine_shared::transform::Transform;
use engine_storage::global_state::StateProvider;

const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_1_BONDED_AMOUNT: u64 = 1_000_000;
const ACCOUNT_1_BALANCE: u64 = 1_000_000_000;
const PAYMENT_AMOUNT: u64 = 200_000_000;
const STANDARD_PAYMENT_CONTRACT_NAME: &str = "standard_payment";
const DO_NOTHING_STORED_CONTRACT_NAME: &str = "do_nothing_stored";
const DO_NOTHING_STORED_CALLER_CONTRACT_NAME: &str = "do_nothing_stored_caller";
const DO_NOTHING_STORED_UPGRADER_CONTRACT_NAME: &str = "do_nothing_stored_upgrader";
const LOCAL_STATE_STORED_CONTRACT_NAME: &str = "local_state_stored";
const LOCAL_STATE_STORED_CALLER_CONTRACT_NAME: &str = "local_state_stored_caller";
const LOCAL_STATE_STORED_UPGRADER_CONTRACT_NAME: &str = "local_state_stored_upgrader";
const PURSE_HOLDER_STORED_CONTRACT_NAME: &str = "purse_holder_stored";
const PURSE_HOLDER_STORED_CALLER_CONTRACT_NAME: &str = "purse_holder_stored_caller";
const PURSE_HOLDER_STORED_UPGRADER_CONTRACT_NAME: &str = "purse_holder_stored_upgrader";
const HELLO: &str = "Hello";
const METHOD_ADD: &str = "add";
const METHOD_REMOVE: &str = "remove";
const METHOD_VERSION: &str = "version";
const PURSE_1: &str = "purse_1";

fn initialize_builder<S>(builder: &mut WasmTestBuilder<S>)
where
    S: StateProvider,
    S::Error: Into<execution::Error>,
    EngineState<S>: ExecutionEngineService,
{
    let account_1 = {
        let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
        let account_1_balance = Motes::new(ACCOUNT_1_BALANCE.into());
        let account_1_bonded_amount = Motes::new(ACCOUNT_1_BONDED_AMOUNT.into());
        GenesisAccount::new(
            account_1_public_key,
            account_1_balance,
            account_1_bonded_amount,
        )
    };

    builder.run_genesis(&create_genesis_config(vec![account_1]));
}

fn exec_session_code<S>(
    builder: &mut WasmTestBuilder<S>,
    account_public_key: &PublicKey,
    account_deploy_counter: u8,
    session_contract_name: &str,
    args: impl contract_ffi::contract_api::argsparser::ArgsParser,
) -> ExecResponse
where
    S: StateProvider,
    S::Error: Into<execution::Error>,
    EngineState<S>: ExecutionEngineService,
{
    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(account_public_key.value())
            .with_session_code(&format!("{}.wasm", session_contract_name), args)
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(PAYMENT_AMOUNT),),
            )
            .with_authorization_keys(&[*account_public_key])
            .with_deploy_hash([account_deploy_counter + 1; 32])
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    builder
        .exec_with_exec_request(exec_request)
        .expect_success() // <- assert equivalent
        .commit();

    let exec_response = builder
        .get_exec_response(account_deploy_counter as usize)
        .expect("there should be a response")
        .clone();

    assert!(exec_response.has_success(), "expected success");

    exec_response
}

fn query_contract<S>(builder: &mut WasmTestBuilder<S>, contract_uref: &URef) -> Option<Contract>
where
    S: StateProvider,
    S::Error: Into<execution::Error>,
    EngineState<S>: ExecutionEngineService,
{
    let contract_value: Value = builder
        .query(None, Key::URef(*contract_uref), &[])
        .expect("should have contract value");

    if let Value::Contract(contract) = contract_value {
        Some(contract)
    } else {
        None
    }
}

#[ignore]
#[test]
fn should_upgrade_do_nothing_to_do_something() {
    let mut builder = {
        let engine_config = EngineConfig::default().set_use_payment_code(true);
        InMemoryWasmTestBuilder::new(engine_config)
    };

    initialize_builder(&mut builder);

    let mut account_deploy_counter: u8 = 0;
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);

    // store do-nothing-stored
    exec_session_code(
        &mut builder,
        &account_1_public_key,
        account_deploy_counter,
        DO_NOTHING_STORED_CONTRACT_NAME,
        (),
    );

    // call stored do nothing, passing a purse name as an arg
    // which should have no affect as do nothing does nothing
    let account_1_transformed = builder
        .get_account(Key::Account(ACCOUNT_1_ADDR))
        .expect("should get account 1");

    assert_eq!(
        account_1_transformed.urefs_lookup().get(PURSE_1),
        None,
        "purse should not exist"
    );

    account_deploy_counter += 1;

    let do_nothing_stored_uref = account_1_transformed
        .urefs_lookup()
        .get(DO_NOTHING_STORED_CONTRACT_NAME)
        .expect("should have do_nothing_stored uref")
        .as_uref()
        .expect("should have uref");

    // do upgrade
    exec_session_code(
        &mut builder,
        &account_1_public_key,
        account_deploy_counter,
        DO_NOTHING_STORED_UPGRADER_CONTRACT_NAME,
        (*do_nothing_stored_uref,),
    );

    account_deploy_counter += 1;

    // call upgraded contract
    exec_session_code(
        &mut builder,
        &account_1_public_key,
        account_deploy_counter,
        DO_NOTHING_STORED_CALLER_CONTRACT_NAME,
        (*do_nothing_stored_uref, PURSE_1),
    );

    let contract =
        query_contract(&mut builder, do_nothing_stored_uref).expect("should have contract");

    // currently as the system is designed the new uref for the purse is added to the
    // caller contract instead of the account...ideally the account would get the uref
    // but that's beyond the scope of this upgrade specific test
    assert!(
        contract.urefs_lookup().contains_key(PURSE_1),
        "should have new purse uref"
    );
}

#[ignore]
#[test]
fn should_be_able_to_observe_state_transition_across_upgrade() {
    let mut builder = {
        let engine_config = EngineConfig::default().set_use_payment_code(true);
        InMemoryWasmTestBuilder::new(engine_config)
    };

    initialize_builder(&mut builder);

    let mut account_deploy_counter: u8 = 0;
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let account_1_key = Key::Account(ACCOUNT_1_ADDR);

    // store do-nothing-stored
    exec_session_code(
        &mut builder,
        &account_1_public_key,
        account_deploy_counter,
        PURSE_HOLDER_STORED_CONTRACT_NAME,
        (),
    );

    account_deploy_counter += 1;

    let account = builder
        .get_account(account_1_key)
        .expect("should have account");

    assert!(
        account.urefs_lookup().contains_key(METHOD_VERSION),
        "version uref should exist on install"
    );

    let stored_uref = account
        .urefs_lookup()
        .get(PURSE_HOLDER_STORED_CONTRACT_NAME)
        .expect("should have stored uref")
        .as_uref()
        .expect("should have uref");

    // verify version before upgrade
    let account = builder
        .get_account(account_1_key)
        .expect("should have account");

    let original_version = builder
        .query(
            None,
            *account
                .urefs_lookup()
                .get(METHOD_VERSION)
                .expect("version uref should exist"),
            &[],
        )
        .expect("version should exist");

    assert_eq!(
        original_version,
        Value::String("1.0.0".to_string()),
        "should be original version"
    );

    // upgrade contract
    exec_session_code(
        &mut builder,
        &account_1_public_key,
        account_deploy_counter,
        PURSE_HOLDER_STORED_UPGRADER_CONTRACT_NAME,
        (*stored_uref,),
    );

    // version should change after upgrade
    let account = builder
        .get_account(account_1_key)
        .expect("should have account");

    let upgraded_version = builder
        .query(
            None,
            *account
                .urefs_lookup()
                .get(METHOD_VERSION)
                .expect("version uref should exist"),
            &[],
        )
        .expect("version should exist");

    assert_eq!(
        upgraded_version,
        Value::String("1.0.1".to_string()),
        "should be original version"
    );
}

#[ignore]
#[test]
fn should_support_extending_functionality() {
    let mut builder = {
        let engine_config = EngineConfig::default().set_use_payment_code(true);
        InMemoryWasmTestBuilder::new(engine_config)
    };

    initialize_builder(&mut builder);

    let mut account_deploy_counter: u8 = 0;
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let account_1_key = Key::Account(ACCOUNT_1_ADDR);

    // store do-nothing-stored
    exec_session_code(
        &mut builder,
        &account_1_public_key,
        account_deploy_counter,
        PURSE_HOLDER_STORED_CONTRACT_NAME,
        (),
    );

    account_deploy_counter += 1;

    let account = builder
        .get_account(account_1_key)
        .expect("should have account");

    let stored_uref = account
        .urefs_lookup()
        .get(PURSE_HOLDER_STORED_CONTRACT_NAME)
        .expect("should have stored uref")
        .as_uref()
        .expect("should have uref");

    // call stored contract and persist a known uref before upgrade
    exec_session_code(
        &mut builder,
        &account_1_public_key,
        account_deploy_counter,
        PURSE_HOLDER_STORED_CALLER_CONTRACT_NAME,
        (*stored_uref, METHOD_ADD, PURSE_1),
    );

    // verify known uref actually exists prior to upgrade
    let contract = query_contract(&mut builder, stored_uref).expect("should have contract");
    assert!(
        contract.urefs_lookup().contains_key(PURSE_1),
        "purse uref should exist in contract's known_urefs before upgrade"
    );

    account_deploy_counter += 1;

    // verify known uref actually exists prior to upgrade
    let contract = query_contract(&mut builder, stored_uref).expect("should have contract");

    assert!(
        contract.urefs_lookup().contains_key(PURSE_1),
        "PURSE_1 uref should exist in contract's known_urefs before upgrade"
    );

    // upgrade contract
    exec_session_code(
        &mut builder,
        &account_1_public_key,
        account_deploy_counter,
        PURSE_HOLDER_STORED_UPGRADER_CONTRACT_NAME,
        (*stored_uref,),
    );

    account_deploy_counter += 1;

    // verify uref still exists in known_urefs after upgrade:
    let contract = query_contract(&mut builder, stored_uref).expect("should have contract");

    assert!(
        contract.urefs_lookup().contains_key(PURSE_1),
        "PURSE_1 uref should still exist in contract's known_urefs after upgrade"
    );

    // call new remove function
    exec_session_code(
        &mut builder,
        &account_1_public_key,
        account_deploy_counter,
        PURSE_HOLDER_STORED_CALLER_CONTRACT_NAME,
        (*stored_uref, METHOD_REMOVE, PURSE_1),
    );

    // verify known urefs no longer include removed purse
    let contract = query_contract(&mut builder, stored_uref).expect("should have contract");

    assert!(
        !contract.urefs_lookup().contains_key(PURSE_1),
        "PURSE_1 uref should no longer exist in contract's known_urefs after remove"
    );
}

#[ignore]
#[test]
fn should_maintain_known_urefs_across_upgrade() {
    let mut builder = {
        let engine_config = EngineConfig::default().set_use_payment_code(true);
        InMemoryWasmTestBuilder::new(engine_config)
    };

    initialize_builder(&mut builder);

    let mut account_deploy_counter = 0;
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let account_1_key = Key::Account(ACCOUNT_1_ADDR);

    // store contract
    exec_session_code(
        &mut builder,
        &account_1_public_key,
        account_deploy_counter,
        PURSE_HOLDER_STORED_CONTRACT_NAME,
        (),
    );

    account_deploy_counter += 1;

    let account = builder
        .get_account(account_1_key)
        .expect("should have account");

    let stored_uref = account
        .urefs_lookup()
        .get(PURSE_HOLDER_STORED_CONTRACT_NAME)
        .expect("should have stored uref")
        .as_uref()
        .expect("should have uref");

    // add several purse urefs to known_urefs
    let total_purses: u8 = 3;
    for index in 1..=total_purses {
        let purse_name: &str = &format!("purse_{}", index);
        exec_session_code(
            &mut builder,
            &account_1_public_key,
            account_deploy_counter,
            PURSE_HOLDER_STORED_CALLER_CONTRACT_NAME,
            (*stored_uref, METHOD_ADD, purse_name),
        );

        // verify known uref actually exists prior to upgrade
        let contract = query_contract(&mut builder, stored_uref).expect("should have contract");
        assert!(
            contract.urefs_lookup().contains_key(purse_name),
            "purse uref should exist in contract's known_urefs before upgrade"
        );

        account_deploy_counter += 1;
    }

    // upgrade contract
    exec_session_code(
        &mut builder,
        &account_1_public_key,
        account_deploy_counter,
        PURSE_HOLDER_STORED_UPGRADER_CONTRACT_NAME,
        (*stored_uref,),
    );

    // verify all urefs still exist in known_urefs after upgrade
    let contract = query_contract(&mut builder, stored_uref).expect("should have contract");

    for index in 1..=total_purses {
        let purse_name: &str = &format!("purse_{}", index);
        assert!(
            contract.urefs_lookup().contains_key(purse_name),
            format!(
                "{} uref should still exist in contract's known_urefs after upgrade",
                index
            )
        );
    }
}

#[ignore]
#[test]
fn should_maintain_local_state_across_upgrade() {
    let mut builder = {
        let engine_config = EngineConfig::default().set_use_payment_code(true);
        InMemoryWasmTestBuilder::new(engine_config)
    };

    initialize_builder(&mut builder);

    let mut account_deploy_counter: u8 = 0;
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let account_1_key = Key::Account(ACCOUNT_1_ADDR);

    // store local_state_stored contract
    exec_session_code(
        &mut builder,
        &account_1_public_key,
        account_deploy_counter,
        LOCAL_STATE_STORED_CONTRACT_NAME,
        (),
    );

    account_deploy_counter += 1;

    let account = builder
        .get_account(account_1_key)
        .expect("should have account");

    let stored_uref = account
        .urefs_lookup()
        .get(LOCAL_STATE_STORED_CONTRACT_NAME)
        .expect("should have stored uref")
        .as_uref()
        .expect("should have uref");

    // call local_state_stored_contract (which will cause it to store some local state)
    exec_session_code(
        &mut builder,
        &account_1_public_key,
        account_deploy_counter,
        LOCAL_STATE_STORED_CALLER_CONTRACT_NAME,
        (*stored_uref,),
    );

    account_deploy_counter += 1;

    // confirm expected local state was written
    let transform_map = &builder.get_transforms()[1];

    let (local_state_key, original_local_state_value) = transform_map
        .iter()
        .find_map(|(key, transform)| match transform {
            Transform::Write(Value::String(s)) if s.contains(HELLO) => Some((*key, s.clone())),
            _ => None,
        })
        .expect("local state Write should exist");

    // upgrade local_state_stored contract
    exec_session_code(
        &mut builder,
        &account_1_public_key,
        account_deploy_counter,
        LOCAL_STATE_STORED_UPGRADER_CONTRACT_NAME,
        (*stored_uref,),
    );

    account_deploy_counter += 1;

    // call upgraded local_state_stored_contract
    // (local state existence is checked in upgraded contract)
    exec_session_code(
        &mut builder,
        &account_1_public_key,
        account_deploy_counter,
        LOCAL_STATE_STORED_CALLER_CONTRACT_NAME,
        (*stored_uref,),
    );

    // get transformed local state value post upgrade
    let transform_map = &builder.get_transforms()[account_deploy_counter as usize];

    let transform = transform_map
        .get(&local_state_key)
        .expect("should have second Write transform");

    let write = {
        match transform {
            Transform::Write(Value::String(s)) => Some(s.to_owned()),
            _ => None,
        }
    }
    .expect("should have write value");

    assert!(
        write.starts_with(&original_local_state_value) && write.ends_with("upgraded!"),
        "local state should include elements from the original version and the upgraded version"
    );
}
