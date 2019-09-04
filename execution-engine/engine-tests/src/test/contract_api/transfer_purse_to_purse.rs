use std::collections::HashMap;

use contract_ffi::key::Key;
use contract_ffi::value::{Value, U512};
use engine_shared::transform::Transform;

use crate::support::test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

const GENESIS_ADDR: [u8; 32] = [12; 32];

#[ignore]
#[test]
fn should_run_purse_to_purse_transfer() {
    let source = "purse:main".to_string();
    let target = "purse:secondary".to_string();

    let transfer_result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_purse_to_purse.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            (source, target, U512::from(42)),
        )
        .expect_success()
        .commit()
        .finish();

    let transforms = transfer_result.builder().get_transforms();
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
            "Transform {:?} is not a Transform with a Value(Account)",
            account_transforms
        );
    };

    // Get the `purse_transfer_result` for a given
    let purse_transfer_result =
        &transform[&modified_account.urefs_lookup()["purse_transfer_result"].normalize()];
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
    let purse_secondary_lookup_key = purse_secondary_key
        .as_uref()
        .unwrap()
        .remove_access_rights()
        .as_string();

    let mint_contract_uref = transfer_result.builder().get_mint_contract_uref();
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
    assert_eq!(main_purse_balance, &U512::from(99_999_999_958i64));
}

#[ignore]
#[test]
fn should_run_purse_to_purse_transfer_with_error() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let source = "purse:main".to_string();
    let target = "purse:secondary".to_string();

    let transfer_result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_purse_to_purse.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            (
                source,
                target,
                // amount
                U512::from(999_999_999_999i64),
            ),
        )
        .expect_success()
        .commit()
        .finish();

    let transforms = transfer_result.builder().get_transforms();
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

    let mint_contract_uref = transfer_result.builder().get_mint_contract_uref();

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
    assert_eq!(main_purse_balance, &U512::from(100_000_000_000i64));
}
