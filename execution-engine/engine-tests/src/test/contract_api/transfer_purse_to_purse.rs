use std::collections::HashMap;

use contract_ffi::key::Key;
use contract_ffi::value::{Value, U512};
use engine_core::engine_state::MAX_PAYMENT;
use engine_shared::transform::Transform;

use crate::support::test_support::{
    InMemoryWasmTestBuilder, DEFAULT_BLOCK_TIME, GENESIS_INITIAL_BALANCE, STANDARD_PAYMENT_CONTRACT,
};

const GENESIS_ADDR: [u8; 32] = [12; 32];
const PURSE_TO_PURSE_AMOUNT: u64 = 42;

#[ignore]
#[test]
fn should_run_purse_to_purse_transfer() {
    let source = "purse:main".to_string();
    let target = "purse:secondary".to_string();

    let transfer_result = InMemoryWasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "transfer_purse_to_purse.wasm",
            (source, target, U512::from(PURSE_TO_PURSE_AMOUNT)),
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit()
        .finish();

    let transforms = transfer_result.builder().get_transforms();
    let transform = &transforms[0];

    let genesis_account_key = Key::Account(GENESIS_ADDR);
    let genesis_account = transfer_result
        .builder()
        .get_account(genesis_account_key)
        .expect("should get genesis account");

    // Get the `purse_transfer_result` for a given
    let purse_transfer_result =
        &transform[&genesis_account.urefs_lookup()["purse_transfer_result"].normalize()];
    let purse_transfer_result = if let Transform::Write(Value::String(s)) = purse_transfer_result {
        s
    } else {
        panic!("Purse transfer result is expected to contain Write with String value");
    };
    // Main assertion for the result of `transfer_from_purse_to_purse`
    assert_eq!(purse_transfer_result, "TransferSuccessful");

    let main_purse_balance =
        &transform[&genesis_account.urefs_lookup()["main_purse_balance"].normalize()];
    let main_purse_balance = if let Transform::Write(Value::UInt512(balance)) = main_purse_balance {
        balance
    } else {
        panic!(
            "Purse transfer result is expected to contain Write with Uint512 value, got {:?}",
            main_purse_balance
        );
    };

    // Assert secondary purse value after successful transfer
    let purse_secondary_key = genesis_account.urefs_lookup()["purse:secondary"];
    let _purse_main_key = genesis_account.urefs_lookup()["purse:main"];

    // Lookup key used to find the actual purse uref
    // TODO: This should be more consistent
    let purse_secondary_lookup_key = purse_secondary_key
        .as_uref()
        .unwrap()
        .remove_access_rights()
        .as_string();

    let mint_contract_uref = transfer_result.builder().get_mint_contract_uref();
    // Obtain transforms for a mint account
    let mint_transforms = transform
        .get(&Key::from(mint_contract_uref).normalize())
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
    assert_eq!(purse_secondary_balance, &U512::from(PURSE_TO_PURSE_AMOUNT));
    assert_eq!(
        main_purse_balance,
        &U512::from(GENESIS_INITIAL_BALANCE - MAX_PAYMENT - PURSE_TO_PURSE_AMOUNT)
    );
}

#[ignore]
#[test]
fn should_run_purse_to_purse_transfer_with_error() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let source = "purse:main".to_string();
    let target = "purse:secondary".to_string();

    let transfer_result = InMemoryWasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "transfer_purse_to_purse.wasm",
            (
                source,
                target,
                // amount
                U512::from(999_999_999_999i64),
            ),
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit()
        .finish();

    let transforms = transfer_result.builder().get_transforms();
    let transform = &transforms[0];

    let genesis_account_key = Key::Account(GENESIS_ADDR);
    let genesis_account = transfer_result
        .builder()
        .get_account(genesis_account_key)
        .expect("should get genesis account");

    // Get the `purse_transfer_result` for a given
    let purse_transfer_result =
        &transform[&genesis_account.urefs_lookup()["purse_transfer_result"].normalize()]; //addkeys["purse_transfer_result"].as_uref().unwrap();
    let purse_transfer_result = if let Transform::Write(Value::String(s)) = purse_transfer_result {
        s
    } else {
        panic!("Purse transfer result is expected to contain Write with String value");
    };
    // Main assertion for the result of `transfer_from_purse_to_purse`
    assert_eq!(purse_transfer_result, "TransferError");

    // Obtain main purse's balance
    let main_purse_balance =
        &transform[&genesis_account.urefs_lookup()["main_purse_balance"].normalize()];
    let main_purse_balance = if let Transform::Write(Value::UInt512(balance)) = main_purse_balance {
        balance
    } else {
        panic!(
            "Purse transfer result is expected to contain Write with Uint512 value, got {:?}",
            main_purse_balance
        );
    };

    let mint_contract_uref = transfer_result.builder().get_mint_contract_uref();

    // Obtain transforms for a mint account
    let mint_transforms = transform
        .get(&Key::from(mint_contract_uref).normalize())
        .expect("Unable to find transforms for a mint");

    // Inspect AddKeys for that account
    let mint_addkeys = if let Transform::AddKeys(value) = mint_transforms {
        value
    } else {
        panic!("Transform {:?} is not AddKeys", mint_transforms);
    };

    // Assert secondary purse value after successful transfer
    let purse_secondary_key = genesis_account.urefs_lookup()["purse:secondary"];
    let _purse_main_key = genesis_account.urefs_lookup()["purse:main"];

    // Lookup key used to find the actual purse uref
    // TODO: This should be more consistent
    let purse_secondary_lookup_key = purse_secondary_key
        .as_uref()
        .unwrap()
        .remove_access_rights()
        .as_string();

    // Find `purse:secondary` for a balance
    let purse_secondary_uref = &mint_addkeys[&purse_secondary_lookup_key];
    let purse_secondary_key: Key = purse_secondary_uref.normalize();
    let purse_secondary = &transform[&purse_secondary_key];
    let purse_secondary_balance = if let Transform::Write(Value::UInt512(value)) = purse_secondary {
        value
    } else {
        panic!("actual purse uref should be a Write of UInt512 type");
    };

    // Final balance of the destination purse equals to 0 as this purse is created
    // as new.
    assert_eq!(purse_secondary_balance, &U512::from(0));
    assert_eq!(
        main_purse_balance,
        &U512::from(100_000_000_000u64 - MAX_PAYMENT)
    );
}
