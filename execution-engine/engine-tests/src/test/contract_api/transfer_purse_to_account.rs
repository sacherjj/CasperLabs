use contract_ffi::key::Key;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::{Value, U512};
use engine_core::engine_state::MAX_PAYMENT;
use engine_shared::transform::Transform;

use crate::support::test_support::{
    InMemoryWasmTestBuilder, DEFAULT_BLOCK_TIME, GENESIS_INITIAL_BALANCE, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

const ACCOUNT_1_ADDR: [u8; 32] = [42u8; 32];
const ACCOUNT_1_INITIAL_FUND: u64 = MAX_PAYMENT + 42;

#[ignore]
#[test]
fn should_run_purse_to_account_transfer() {
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let genesis_public_key = PublicKey::new(DEFAULT_ACCOUNT_ADDR);

    let transfer_result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_args(
            DEFAULT_ACCOUNT_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "transfer_purse_to_account.wasm",
            (account_1_public_key, U512::from(ACCOUNT_1_INITIAL_FUND)),
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit()
        .exec_with_args(
            account_1_public_key.value(),
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "transfer_purse_to_account.wasm",
            (genesis_public_key, U512::from(1)),
            DEFAULT_BLOCK_TIME,
            [2u8; 32],
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
    let default_account = transfer_result
        .builder()
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get genesis account");

    // Obtain main purse's balance
    let final_balance = &transform[&default_account.urefs_lookup()["final_balance"].normalize()];
    let final_balance = if let Transform::Write(Value::UInt512(balance)) = final_balance {
        balance
    } else {
        panic!(
            "Purse transfer result is expected to contain Write with Uint512 value, got {:?}",
            final_balance
        );
    };
    assert_eq!(
        final_balance,
        &U512::from(GENESIS_INITIAL_BALANCE - (MAX_PAYMENT * 2) - 42)
    );

    // Get the `transfer_result` for a given account
    let transfer_result_transform =
        &transform[&default_account.urefs_lookup()["transfer_result"].normalize()];
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
            new_account_transforms
        );
    };

    let new_purse_id = new_account.purse_id();
    // This is the new PurseId lookup key that will be present in AddKeys for a mint
    // contract uref
    let new_purse_id_lookup_key = new_purse_id.value().remove_access_rights().as_string();

    // Obtain transforms for a mint account
    let mint_contract_uref = transfer_result.builder().get_mint_contract_uref();

    let mint_transforms = transform
        .get(&Key::from(mint_contract_uref).normalize())
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
    assert_eq!(purse_secondary_balance, &U512::from(MAX_PAYMENT + 42));

    //
    // Exec 2 - Transfer from new account back to genesis to verify
    // TransferToExisting

    let transforms = transfer_result.builder().get_transforms();
    let transform = &transforms[1];

    let account_1 = transfer_result
        .builder()
        .get_account(ACCOUNT_1_ADDR)
        .expect("should get account 1");

    // Obtain main purse's balance
    let final_balance = &transform[&account_1.urefs_lookup()["final_balance"].normalize()];
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
        &transform[&account_1.urefs_lookup()["transfer_result"].normalize()];
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
        .get(&Key::Account(DEFAULT_ACCOUNT_ADDR))
        .expect("Unable to find transforms for a genesis account");

    // Genesis account is unchanged
    assert_eq!(genesis_transforms, &Transform::Identity);

    let genesis_transforms = transfer_result.builder().get_genesis_transforms();

    let balance_uref = genesis_transforms
        .iter()
        .find_map(|(k, t)| match (k, t) {
            (uref @ Key::URef(_), Transform::Write(Value::UInt512(x)))
                if *x == U512::from(100_000_000_000i64) =>
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

    let transfer_result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_args(
            DEFAULT_ACCOUNT_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "transfer_purse_to_account.wasm",
            (account_1_key, U512::max_value()),
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit()
        .finish();

    let transforms = transfer_result.builder().get_transforms();
    let transform = &transforms[0];

    // Get transforms output for genesis account
    let default_account = transfer_result
        .builder()
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get genesis account");

    // Obtain main purse's balance
    let final_balance = &transform[&default_account.urefs_lookup()["final_balance"].normalize()];
    let final_balance = if let Transform::Write(Value::UInt512(balance)) = final_balance {
        balance
    } else {
        panic!(
            "Purse transfer result is expected to contain Write with Uint512 value, got {:?}",
            final_balance
        );
    };
    // When trying to send too much coins the balance is left unchanged
    assert_eq!(
        final_balance,
        &U512::from(100_000_000_000u64 - MAX_PAYMENT),
        "final balance incorrect"
    );

    // Get the `transfer_result` for a given account
    let transfer_result_transform =
        &transform[&default_account.urefs_lookup()["transfer_result"].normalize()];
    let transfer_result_string =
        if let Transform::Write(Value::String(s)) = transfer_result_transform {
            s
        } else {
            panic!("Purse transfer result is expected to contain Write with String value");
        };

    // Main assertion for the result of `transfer_from_purse_to_purse`
    assert_eq!(
        transfer_result_string, "TransferError",
        "TransferError incorrect"
    );
}
