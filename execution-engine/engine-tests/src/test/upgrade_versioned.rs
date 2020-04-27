use engine_test_support::{
    internal::{ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_GENESIS_CONFIG},
    DEFAULT_ACCOUNT_ADDR,
};
use types::{runtime_args, RuntimeArgs, SemVer};

const DO_NOTHING_STORED_CONTRACT_NAME: &str = "do_nothing_stored";
const DO_NOTHING_STORED_UPGRADER_CONTRACT_NAME: &str = "do_nothing_stored_upgrader";
const DO_NOTHING_STORED_CALLER_CONTRACT_NAME: &str = "do_nothing_stored_caller";

const ENTRY_FUNCTION_NAME: &str = "delegate";
const DO_NOTHING_HASH_KEY_NAME: &str = "do_nothing_hash";
const INITIAL_VERSION: SemVer = SemVer::new(1, 0, 0);
const UPGRADED_VERSION: SemVer = SemVer::new(2, 0, 0);
const PURSE_NAME_ARG_NAME: &str = "purse_name";
const PURSE_1: &str = "purse_1";

/// Performs define and execution of versioned contracts, calling them directly from hash
#[ignore]
#[test]
fn should_upgrade_do_nothing_to_do_something_version_hash_call() {
    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    // Create contract metadata and store contract ver: 1.0.0 with "delegate" entry function
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", DO_NOTHING_STORED_CONTRACT_NAME);
            ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, ()).build()
        };

        builder.exec(exec_request).expect_success().commit();
    }

    // Calling initial version from contract metadata hash, should have no effects
    {
        let exec_request = {
            ExecuteRequestBuilder::versioned_contract_call_by_hash_key_name(
                DEFAULT_ACCOUNT_ADDR,
                DO_NOTHING_HASH_KEY_NAME,
                INITIAL_VERSION,
                ENTRY_FUNCTION_NAME,
                RuntimeArgs::new(),
            )
            .build()
        };

        builder.exec(exec_request).expect_success().commit();
    }

    let account_1 = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get account 1");

    assert!(
        account_1.named_keys().get(PURSE_1).is_none(),
        "purse should not exist",
    );

    // Upgrade version having call to create_purse_01
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", DO_NOTHING_STORED_UPGRADER_CONTRACT_NAME);
            ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, ()).build()
        };

        builder.exec(exec_request).expect_success().commit();
    }

    // Calling upgraded version, expecting purse creation
    {
        let args = runtime_args! {
            PURSE_NAME_ARG_NAME => PURSE_1,
        };
        let exec_request = {
            ExecuteRequestBuilder::versioned_contract_call_by_hash_key_name(
                DEFAULT_ACCOUNT_ADDR,
                DO_NOTHING_HASH_KEY_NAME,
                UPGRADED_VERSION,
                ENTRY_FUNCTION_NAME,
                args,
            )
            .build()
        };

        builder.exec(exec_request).expect_success().commit();
    }

    let account_1 = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get account 1");

    assert!(
        account_1.named_keys().get(PURSE_1).is_some(),
        "purse should exist",
    );
}

/// Performs define and execution of versioned contracts, calling them from a contract
#[ignore]
#[test]
fn should_upgrade_do_nothing_to_do_something_contract_call() {
    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    // Create contract metadata and store contract ver: 1.0.0
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", DO_NOTHING_STORED_CONTRACT_NAME);
            ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, ()).build()
        };

        builder.exec(exec_request).expect_success().commit();
    }

    let account_1 = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get account 1");

    let stored_contract_hash = account_1
        .named_keys()
        .get(DO_NOTHING_HASH_KEY_NAME)
        .expect("should have key of do_nothing_hash");

    // TODO This does not seem to call properly
    // Calling initial stored version from contract metadata hash, should have no effects
    {
        let contract_name = format!("{}.wasm", DO_NOTHING_STORED_CALLER_CONTRACT_NAME);
        // TODO update to pass SemVer once supported into contracts, instead of
        // INITIAL_VERSION.major
        let args = (*stored_contract_hash, INITIAL_VERSION.major, PURSE_1);
        let exec_request =
            { ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, args).build() };

        builder.exec(exec_request).expect_success().commit();
    }

    let account_1 = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get account 1");

    assert!(
        account_1.named_keys().get(PURSE_1).is_none(),
        "purse should not exist",
    );

    // Upgrade stored contract to version: 2.0.0, having call to create_purse_01
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", DO_NOTHING_STORED_UPGRADER_CONTRACT_NAME);
            ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, ()).build()
        };

        builder.exec(exec_request).expect_success().commit();
    }

    let stored_contract_hash = account_1
        .named_keys()
        .get(DO_NOTHING_HASH_KEY_NAME)
        .expect("should have key of do_nothing_hash");

    // TODO This does not seem to call properly
    // Calling upgraded stored version, expecting purse creation
    {
        let contract_name = format!("{}.wasm", DO_NOTHING_STORED_CALLER_CONTRACT_NAME);
        // TODO update to pass SemVer once supported into contracts, instead of
        // UPGRADED_VERSION.major
        let args = (*stored_contract_hash, UPGRADED_VERSION.major, PURSE_1);
        let exec_request =
            { ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, args).build() };

        builder.exec(exec_request).expect_success().commit();
    }

    let account_1 = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get account 1");

    assert!(
        account_1.named_keys().get(PURSE_1).is_some(),
        "purse should exist",
    );
}
