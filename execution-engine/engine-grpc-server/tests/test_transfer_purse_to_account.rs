extern crate grpc;

extern crate casperlabs_engine_grpc_server;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;

use std::collections::HashMap;

use contract_ffi::key::Key;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::{Value, U512};
use engine_shared::transform::Transform;

use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

#[allow(dead_code)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [12; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [42u8; 32];

#[ignore]
#[test]
fn should_run_purse_to_account_transfer() {
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let genesis_public_key = PublicKey::new(GENESIS_ADDR);

    let transfer_result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_purse_to_account.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            (account_1_public_key, U512::from(42)),
        )
        .expect_success()
        .commit()
        .exec_with_args(
            account_1_public_key.value(),
            "transfer_purse_to_account.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            (genesis_public_key, U512::from(1)),
        )
        .expect_success()
        .commit()
        .finish();

    //
    // Exec 1 - New account [42; 32] is created
    //

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

    // Obtain main purse's balance
    let final_balance = &transform[&modified_account.urefs_lookup()["final_balance"].normalize()];
    let final_balance = if let Transform::Write(Value::UInt512(balance)) = final_balance {
        balance
    } else {
        panic!(
            "Purse transfer result is expected to contain Write with Uint512 value, got {:?}",
            final_balance
        );
    };
    assert_eq!(final_balance, &U512::from(999_958));

    // Get the `transfer_result` for a given account
    let transfer_result_transform =
        &transform[&modified_account.urefs_lookup()["transfer_result"].normalize()];
    let transfer_result_string =
        if let Transform::Write(Value::String(s)) = transfer_result_transform {
            s
        } else {
            panic!("Purse transfer result is expected to contain Write with String value");
        };
    // Main assertion for the result of `transfer_from_purse_to_purse`
    assert_eq!(transfer_result_string, "TransferredToNewAccount");

    // Get transforms output for new account
    let new_account_transforms = transform
        .get(&Key::Account(ACCOUNT_1_ADDR))
        .expect("Unable to find transforms for a genesis account");

    // Inspect AddKeys for that new account to find it's purse id
    let new_account = if let Transform::Write(Value::Account(account)) = new_account_transforms {
        account
    } else {
        panic!(
            "Transform {:?} is not a Transform with a Value(Account)",
            account_transforms
        );
    };

    let new_purse_id = new_account.purse_id();
    // This is the new PurseId lookup key that will be present in AddKeys for a mint contract uref
    let new_purse_id_lookup_key = new_purse_id.value().remove_access_rights().as_string();

    // Obtain transforms for a mint account
    let mint_contract_uref = transfer_result.builder().get_mint_contract_uref();
    let mint_transforms = transform
        .get(&mint_contract_uref.into())
        .expect("Unable to find transforms for a mint");

    // Inspect AddKeyse entry for that new account inside mint transforms
    let mint_addkeys = if let Transform::AddKeys(value) = mint_transforms {
        value
    } else {
        panic!("Transform {:?} is not AddKeys", mint_transforms);
    };

    // Find new account's purse uref
    let new_account_purse_uref = &mint_addkeys[&new_purse_id_lookup_key];

    let new_purse_transform = &transform[&new_account_purse_uref.normalize()];
    let purse_secondary_balance =
        if let Transform::Write(Value::UInt512(value)) = new_purse_transform {
            value
        } else {
            panic!("actual purse uref should be a Write of UInt512 type");
        };
    assert_eq!(purse_secondary_balance, &U512::from(42));

    //
    // Exec 2 - Transfer from new account back to genesis to verify TransferToExisting

    let transforms = transfer_result.builder().get_transforms();
    let transform = &transforms[1];

    // Get transforms output for genesis account
    let account_transforms = transform
        .get(&Key::Account(ACCOUNT_1_ADDR))
        .expect("Unable to find transforms for a new account");

    // Inspect AddKeys for that account
    let modified_account = if let Transform::Write(Value::Account(account)) = account_transforms {
        account
    } else {
        panic!(
            "Transform {:?} is not a Transform with a Value(Account)",
            account_transforms
        );
    };

    // Obtain main purse's balance
    let final_balance = &transform[&modified_account.urefs_lookup()["final_balance"].normalize()];
    let final_balance = if let Transform::Write(Value::UInt512(balance)) = final_balance {
        balance
    } else {
        panic!(
            "Purse transfer result is expected to contain Write with Uint512 value, got {:?}",
            final_balance
        );
    };
    assert_eq!(final_balance, &U512::from(41));

    // Get the `transfer_result` for a given account
    let transfer_result_transform =
        &transform[&modified_account.urefs_lookup()["transfer_result"].normalize()];
    let transfer_result_string =
        if let Transform::Write(Value::String(s)) = transfer_result_transform {
            s
        } else {
            panic!("Purse transfer result is expected to contain Write with String value");
        };
    // Main assertion for the result of `transfer_from_purse_to_purse`
    assert_eq!(transfer_result_string, "TransferredToExistingAccount");

    // Get transforms output for genesis
    let genesis_transforms = transform
        .get(&Key::Account(GENESIS_ADDR))
        .expect("Unable to find transforms for a genesis account");

    // Genesis account is unchanged
    assert_eq!(genesis_transforms, &Transform::Identity);

    let genesis_transforms = transfer_result.builder().get_genesis_transforms();

    let balance_uref = genesis_transforms
        .iter()
        .find_map(|(k, t)| match (k, t) {
            (uref @ Key::URef(_), Transform::Write(Value::UInt512(x)))
                if *x == U512::from(1_000_000) =>
            // 1_000_000 is the initial balance of genesis
            {
                Some(*uref)
            }
            _ => None,
        })
        .expect("Could not find genesis account balance uref");

    let updated_balance = &transform[&balance_uref.normalize()];
    assert_eq!(updated_balance, &Transform::AddUInt512(U512::from(1)));
}

#[ignore]
#[test]
fn should_fail_when_sending_too_much_from_purse_to_account() {
    let account_1_key = PublicKey::new(ACCOUNT_1_ADDR);

    let transfer_result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_purse_to_account.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            (account_1_key, U512::max_value()),
        )
        .expect_success()
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
    // Obtain main purse's balance
    let final_balance = &transform[&modified_account.urefs_lookup()["final_balance"].normalize()];
    let final_balance = if let Transform::Write(Value::UInt512(balance)) = final_balance {
        balance
    } else {
        panic!(
            "Purse transfer result is expected to contain Write with Uint512 value, got {:?}",
            final_balance
        );
    };
    // When trying to send too much coins the balance is left unchanged
    assert_eq!(final_balance, &U512::from(1_000_000));

    // Get the `transfer_result` for a given account
    let transfer_result_transform =
        &transform[&modified_account.urefs_lookup()["transfer_result"].normalize()];
    let transfer_result_string =
        if let Transform::Write(Value::String(s)) = transfer_result_transform {
            s
        } else {
            panic!("Purse transfer result is expected to contain Write with String value");
        };

    // Main assertion for the result of `transfer_from_purse_to_purse`
    assert_eq!(transfer_result_string, "TransferError");
}
