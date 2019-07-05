extern crate grpc;

extern crate casperlabs_engine_grpc_server;
extern crate common;
extern crate execution_engine;
extern crate shared;
extern crate storage;

#[allow(dead_code)]
mod test_support;

use common::key::Key;
use common::value::{Value, U512};
use shared::transform::Transform;

use std::collections::HashMap;
use test_support::WasmTestBuilder;

const GENESIS_ADDR: [u8; 32] = [12; 32];

#[ignore]
#[test]
fn should_run_purse_to_purse_transfer() {
    let genesis_test_result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .finish();

    let _genesis_account = genesis_test_result.genesis_account.clone();
    let mint_contract_uref = genesis_test_result
        .mint_contract_uref
        .expect("Unable to get mint contract uref");

    let source = "purse:main".to_string();
    let target = "purse:secondary".to_string();

    let transfer_result = WasmTestBuilder::from_result(genesis_test_result)
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_purse_to_purse.wasm",
            1,
            (source, target, U512::from(42)),
        )
        .expect_success()
        .commit()
        .finish();

    let transforms = transfer_result.get_transforms();
    let transform = &transforms[0];

    // Get transforms output for genesis account
    let account_transforms = transform
        .get(&Key::Account(GENESIS_ADDR))
        .expect("Unable to find transforms for a genesis account");

    // Inspect AddKeys for that account
    let modified_account = if let Transform::Write(Value::Account(account)) = account_transforms {
        account
    } else {
        panic!(
            "Transform {:?} is not a Transform with a Value of Account type",
            account_transforms
        );
    };

    // Get the `purse_transfer_result` for a given
    let purse_transfer_result =
        &transform[&modified_account.urefs_lookup()["purse_transfer_result"].normalize()]; //addkeys["purse_transfer_result"].as_uref().unwrap();
    let purse_transfer_result = if let Transform::Write(Value::String(s)) = purse_transfer_result {
        s
    } else {
        panic!("Purse transfer result is expected to contain Write with String value");
    };
    // Main assertion for the result of `transfer_from_purse_to_purse`
    assert_eq!(purse_transfer_result, "TransferSuccessful");

    let main_purse_balance =
        &transform[&modified_account.urefs_lookup()["main_purse_balance"].normalize()];
    let main_purse_balance = if let Transform::Write(Value::UInt512(balance)) = main_purse_balance {
        balance
    } else {
        panic!(
            "Purse transfer result is expected to contain Write with Uint512 value, got {:?}",
            main_purse_balance
        );
    };

    // Assert secondary purse value after successful transfer
    let purse_secondary_key = modified_account.urefs_lookup()["purse:secondary"];
    let _purse_main_key = modified_account.urefs_lookup()["purse:main"];

    // Lookup key used to find the actual purse uref
    // TODO: This should be more consistent
    let purse_secondary_lookup_key = format!("{:?}", purse_secondary_key.as_uref().unwrap().addr());

    // Obtain transforms for a mint account
    let mint_transforms = transform
        .get(&mint_contract_uref.into())
        .expect("Unable to find transforms for a mint");

    // Inspect AddKeys for that account
    let mint_addkeys = if let Transform::AddKeys(value) = mint_transforms {
        value
    } else {
        panic!("Transform {:?} is not AddKeys", mint_transforms);
    };

    // Find `purse:secondary`.
    let purse_secondary_uref = &mint_addkeys[&purse_secondary_lookup_key];
    let purse_secondary_key: Key = purse_secondary_uref.normalize();
    let purse_secondary = &transform[&purse_secondary_key];
    let purse_secondary_balance = if let Transform::Write(Value::UInt512(value)) = purse_secondary {
        value
    } else {
        panic!("actual purse uref should be a Write of UInt512 type");
    };

    // Final balance of the destination purse
    assert_eq!(purse_secondary_balance, &U512::from(42));
    assert_eq!(main_purse_balance, &U512::from(999_958));
}

#[ignore]
#[test]
fn should_run_purse_to_purse_transfer_with_error() {
    // This test runs a contract that's after every call extends the same key with more data
    let genesis_test_result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .finish();

    let _genesis_account = genesis_test_result.genesis_account.clone();
    let mint_contract_uref = genesis_test_result
        .mint_contract_uref
        .expect("Unable to get mint contract uref");

    let source = "purse:main".to_string();
    let target = "purse:secondary".to_string();

    let transfer_result = WasmTestBuilder::from_result(genesis_test_result)
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_purse_to_purse.wasm",
            1,
            (
                // source
                source,
                // dest
                target,
                // amount
                U512::from(9_999_999),
            ),
        )
        .expect_success()
        .commit()
        .finish();

    let transforms = transfer_result.get_transforms();
    let transform = &transforms[0];

    // Get transforms output for genesis account
    let account_transforms = transform
        .get(&Key::Account(GENESIS_ADDR))
        .expect("Unable to find transforms for a genesis account");

    // Inspect AddKeys for that account
    let modified_account = if let Transform::Write(Value::Account(account)) = account_transforms {
        account
    } else {
        panic!(
            "Transform {:?} is not a Transform with a Value of Account type",
            account_transforms
        );
    };

    // Get the `purse_transfer_result` for a given
    let purse_transfer_result =
        &transform[&modified_account.urefs_lookup()["purse_transfer_result"].normalize()]; //addkeys["purse_transfer_result"].as_uref().unwrap();
    let purse_transfer_result = if let Transform::Write(Value::String(s)) = purse_transfer_result {
        s
    } else {
        panic!("Purse transfer result is expected to contain Write with String value");
    };
    // Main assertion for the result of `transfer_from_purse_to_purse`
    assert_eq!(purse_transfer_result, "TransferError");

    // Obtain main purse's balance
    let main_purse_balance =
        &transform[&modified_account.urefs_lookup()["main_purse_balance"].normalize()];
    let main_purse_balance = if let Transform::Write(Value::UInt512(balance)) = main_purse_balance {
        balance
    } else {
        panic!(
            "Purse transfer result is expected to contain Write with Uint512 value, got {:?}",
            main_purse_balance
        );
    };

    // Obtain transforms for a mint account
    let mint_transforms = transform
        .get(&mint_contract_uref.into())
        .expect("Unable to find transforms for a mint");

    // Inspect AddKeys for that account
    let mint_addkeys = if let Transform::AddKeys(value) = mint_transforms {
        value
    } else {
        panic!("Transform {:?} is not AddKeys", mint_transforms);
    };

    // Assert secondary purse value after successful transfer
    let purse_secondary_key = modified_account.urefs_lookup()["purse:secondary"];
    let _purse_main_key = modified_account.urefs_lookup()["purse:main"];

    // Lookup key used to find the actual purse uref
    // TODO: This should be more consistent
    let purse_secondary_lookup_key = format!("{:?}", purse_secondary_key.as_uref().unwrap().addr());

    // Find `purse:secondary` for a balance
    let purse_secondary_uref = &mint_addkeys[&purse_secondary_lookup_key];
    let purse_secondary_key: Key = purse_secondary_uref.normalize();
    let purse_secondary = &transform[&purse_secondary_key];
    let purse_secondary_balance = if let Transform::Write(Value::UInt512(value)) = purse_secondary {
        value
    } else {
        panic!("actual purse uref should be a Write of UInt512 type");
    };

    // Final balance of the destination purse equals to 0 as this purse is created as new.
    assert_eq!(purse_secondary_balance, &U512::from(0));
    assert_eq!(main_purse_balance, &U512::from(1_000_000));
}
