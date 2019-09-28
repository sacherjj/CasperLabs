use contract_ffi::value::Value;
use engine_core::engine_state::EngineConfig;
use engine_shared::transform::Transform;

use crate::support::test_support::{ExecRequestBuilder, InMemoryWasmTestBuilder};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

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
const TOTAL_PURSES: usize = 3;

#[ignore]
#[test]
fn should_upgrade_do_nothing_to_do_something() {
    let mut builder = {
        let engine_config = EngineConfig::default().set_use_payment_code(true);
        InMemoryWasmTestBuilder::new(engine_config)
    };

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    {
        let exec_request = {
            let contract_name = format!("{}.wasm", DO_NOTHING_STORED_CONTRACT_NAME);
            ExecRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, ())
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();
    }

    // call stored do nothing, passing a purse name as an arg
    // which should have no affect as do nothing does nothing
    let account_1_transformed = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get account 1");

    assert!(
        account_1_transformed.urefs_lookup().get(PURSE_1).is_none(),
        "purse should not exist",
    );

    let do_nothing_stored_uref = account_1_transformed
        .urefs_lookup()
        .get(DO_NOTHING_STORED_CONTRACT_NAME)
        .expect("should have do_nothing_stored uref")
        .as_uref()
        .expect("should have uref");

    // do upgrade
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", DO_NOTHING_STORED_UPGRADER_CONTRACT_NAME);
            ExecRequestBuilder::standard(
                DEFAULT_ACCOUNT_ADDR,
                &contract_name,
                (*do_nothing_stored_uref,),
            )
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();
    }

    // call upgraded contract
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", DO_NOTHING_STORED_CALLER_CONTRACT_NAME);
            ExecRequestBuilder::standard(
                DEFAULT_ACCOUNT_ADDR,
                &contract_name,
                (*do_nothing_stored_uref, PURSE_1),
            )
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();
    }

    let contract = builder
        .get_contract(*do_nothing_stored_uref)
        .expect("should have contract");

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

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    // store do-nothing-stored
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", PURSE_HOLDER_STORED_CONTRACT_NAME);
            ExecRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, ())
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();
    }

    let account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
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
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should have account");

    let version = *account
        .urefs_lookup()
        .get(METHOD_VERSION)
        .expect("version uref should exist");

    let original_version = builder
        .query(None, version, &[])
        .expect("version should exist");

    assert_eq!(
        original_version,
        Value::String("1.0.0".to_string()),
        "should be original version"
    );

    // upgrade contract
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", PURSE_HOLDER_STORED_UPGRADER_CONTRACT_NAME);
            ExecRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, (*stored_uref,))
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();
    }

    // version should change after upgrade
    let account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should have account");

    let version = *account
        .urefs_lookup()
        .get(METHOD_VERSION)
        .expect("version key should exist");

    let upgraded_version = builder
        .query(None, version, &[])
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

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    // store do-nothing-stored
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", PURSE_HOLDER_STORED_CONTRACT_NAME);
            ExecRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, ())
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();
    }

    let account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should have account");

    let stored_uref = account
        .urefs_lookup()
        .get(PURSE_HOLDER_STORED_CONTRACT_NAME)
        .expect("should have stored uref")
        .as_uref()
        .expect("should have uref");

    // call stored contract and persist a known uref before upgrade
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", PURSE_HOLDER_STORED_CALLER_CONTRACT_NAME);
            ExecRequestBuilder::standard(
                DEFAULT_ACCOUNT_ADDR,
                &contract_name,
                (*stored_uref, METHOD_ADD, PURSE_1),
            )
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();
    }

    // verify known uref actually exists prior to upgrade
    let contract = builder
        .get_contract(*stored_uref)
        .expect("should have contract");
    assert!(
        contract.urefs_lookup().contains_key(PURSE_1),
        "purse uref should exist in contract's known_urefs before upgrade"
    );

    // upgrade contract
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", PURSE_HOLDER_STORED_UPGRADER_CONTRACT_NAME);
            ExecRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, (*stored_uref,))
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();
    }

    // verify uref still exists in known_urefs after upgrade:
    let contract = builder
        .get_contract(*stored_uref)
        .expect("should have contract");

    assert!(
        contract.urefs_lookup().contains_key(PURSE_1),
        "PURSE_1 uref should still exist in contract's known_urefs after upgrade"
    );

    // call new remove function
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", PURSE_HOLDER_STORED_CALLER_CONTRACT_NAME);
            ExecRequestBuilder::standard(
                DEFAULT_ACCOUNT_ADDR,
                &contract_name,
                (*stored_uref, METHOD_REMOVE, PURSE_1),
            )
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();
    }

    // verify known urefs no longer include removed purse
    let contract = builder
        .get_contract(*stored_uref)
        .expect("should have contract");

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

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    // store contract
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", PURSE_HOLDER_STORED_CONTRACT_NAME);
            ExecRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, ())
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();
    }

    let account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should have account");

    let stored_uref = account
        .urefs_lookup()
        .get(PURSE_HOLDER_STORED_CONTRACT_NAME)
        .expect("should have stored uref")
        .as_uref()
        .expect("should have uref");

    // add several purse urefs to known_urefs
    for index in 0..TOTAL_PURSES {
        let purse_name: &str = &format!("purse_{}", index);

        let exec_request = {
            let contract_name = format!("{}.wasm", PURSE_HOLDER_STORED_CALLER_CONTRACT_NAME);
            ExecRequestBuilder::standard(
                DEFAULT_ACCOUNT_ADDR,
                &contract_name,
                (*stored_uref, METHOD_ADD, purse_name),
            )
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();

        // verify known uref actually exists prior to upgrade
        let contract = builder
            .get_contract(*stored_uref)
            .expect("should have contract");
        assert!(
            contract.urefs_lookup().contains_key(purse_name),
            "purse uref should exist in contract's known_urefs before upgrade"
        );
    }

    // upgrade contract
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", PURSE_HOLDER_STORED_UPGRADER_CONTRACT_NAME);
            ExecRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, (*stored_uref,))
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();
    }

    // verify all urefs still exist in known_urefs after upgrade
    let contract = builder
        .get_contract(*stored_uref)
        .expect("should have contract");

    for index in 0..TOTAL_PURSES {
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

    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    // store local_state_stored contract
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", LOCAL_STATE_STORED_CONTRACT_NAME);
            ExecRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, ())
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();
    }

    let account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should have account");

    let stored_uref = account
        .urefs_lookup()
        .get(LOCAL_STATE_STORED_CONTRACT_NAME)
        .expect("should have stored uref")
        .as_uref()
        .expect("should have uref");

    // call local_state_stored_contract (which will cause it to store some local state)
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", LOCAL_STATE_STORED_CALLER_CONTRACT_NAME);
            ExecRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, (*stored_uref,))
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();
    }

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
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", LOCAL_STATE_STORED_UPGRADER_CONTRACT_NAME);
            ExecRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, (*stored_uref,))
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();
    }

    // call upgraded local_state_stored_contract
    // (local state existence is checked in upgraded contract)
    {
        let exec_request = {
            let contract_name = format!("{}.wasm", LOCAL_STATE_STORED_CALLER_CONTRACT_NAME);
            ExecRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, (*stored_uref,))
        };

        builder
            .exec_with_exec_request(exec_request)
            .expect_success()
            .commit();
    }

    // get transformed local state value post upgrade
    let transforms = builder.get_transforms();

    let transform = transforms
        .last()
        .expect("should have transforms")
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
