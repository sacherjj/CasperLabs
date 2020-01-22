use engine_shared::{stored_value::StoredValue, transform::Transform};
use types::{ApiError, Key, U512};

use engine_test_support::low_level::{
    ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_ACCOUNT_INITIAL_BALANCE, DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT,
};

const CONTRACT_TRANSFER_PURSE_TO_PURSE: &str = "transfer_purse_to_purse.wasm";
const PURSE_TO_PURSE_AMOUNT: u64 = 42;

#[ignore]
#[test]
fn should_run_purse_to_purse_transfer() {
    let source = "purse:main".to_string();
    let target = "purse:secondary".to_string();

    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_PURSE,
        (source, target, U512::from(PURSE_TO_PURSE_AMOUNT)),
    )
    .build();

    let transfer_result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .finish();

    let transforms = transfer_result.builder().get_transforms();
    let transform = &transforms[0];

    let default_account = transfer_result
        .builder()
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get genesis account");

    // Get the `purse_transfer_result` for a given
    let purse_transfer_result =
        &transform[&default_account.named_keys()["purse_transfer_result"].normalize()];
    let purse_transfer_result =
        if let Transform::Write(StoredValue::CLValue(cl_value)) = purse_transfer_result {
            cl_value
                .to_owned()
                .into_t::<String>()
                .expect("should be String")
        } else {
            panic!("Purse transfer result is expected to contain Write with String value");
        };
    // Main assertion for the result of `transfer_from_purse_to_purse`
    assert_eq!(
        purse_transfer_result,
        format!("{:?}", Result::<_, ApiError>::Ok(()),)
    );

    let main_purse_balance =
        &transform[&default_account.named_keys()["main_purse_balance"].normalize()];
    let main_purse_balance =
        if let Transform::Write(StoredValue::CLValue(cl_value)) = main_purse_balance {
            cl_value
                .to_owned()
                .into_t::<U512>()
                .expect("should be U512")
        } else {
            panic!(
                "Purse transfer result is expected to contain Write with Uint512 value, got {:?}",
                main_purse_balance
            );
        };

    // Assert secondary purse value after successful transfer
    let purse_secondary_key = default_account.named_keys()["purse:secondary"];
    let _purse_main_key = default_account.named_keys()["purse:main"];

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
    let purse_secondary_balance =
        if let Transform::Write(StoredValue::CLValue(cl_value)) = purse_secondary {
            cl_value
                .to_owned()
                .into_t::<U512>()
                .expect("should be U512")
        } else {
            panic!("actual purse uref should be a Write of UInt512 type");
        };

    // Final balance of the destination purse
    assert_eq!(purse_secondary_balance, U512::from(PURSE_TO_PURSE_AMOUNT));
    assert_eq!(
        main_purse_balance,
        U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE) - *DEFAULT_PAYMENT - PURSE_TO_PURSE_AMOUNT
    );
}

#[ignore]
#[test]
fn should_run_purse_to_purse_transfer_with_error() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let source = "purse:main".to_string();
    let target = "purse:secondary".to_string();
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_PURSE,
        (source, target, U512::from(999_999_999_999i64)),
    )
    .build();
    let transfer_result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .finish();

    let transforms = transfer_result.builder().get_transforms();
    let transform = &transforms[0];

    let default_account = transfer_result
        .builder()
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get genesis account");

    // Get the `purse_transfer_result` for a given
    let purse_transfer_result =
        &transform[&default_account.named_keys()["purse_transfer_result"].normalize()]; //addkeys["purse_transfer_result"].as_uref().unwrap();
    let purse_transfer_result =
        if let Transform::Write(StoredValue::CLValue(cl_value)) = purse_transfer_result {
            cl_value
                .to_owned()
                .into_t::<String>()
                .expect("should be String")
        } else {
            panic!("Purse transfer result is expected to contain Write with String value");
        };
    // Main assertion for the result of `transfer_from_purse_to_purse`
    assert_eq!(
        purse_transfer_result,
        format!("{:?}", Result::<(), _>::Err(ApiError::Transfer)),
    );

    // Obtain main purse's balance
    let main_purse_balance =
        &transform[&default_account.named_keys()["main_purse_balance"].normalize()];
    let main_purse_balance =
        if let Transform::Write(StoredValue::CLValue(cl_value)) = main_purse_balance {
            cl_value
                .to_owned()
                .into_t::<U512>()
                .expect("should be U512")
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
    let purse_secondary_key = default_account.named_keys()["purse:secondary"];
    let _purse_main_key = default_account.named_keys()["purse:main"];

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
    let purse_secondary_balance =
        if let Transform::Write(StoredValue::CLValue(cl_value)) = purse_secondary {
            cl_value
                .to_owned()
                .into_t::<U512>()
                .expect("should be U512")
        } else {
            panic!("actual purse uref should be a Write of UInt512 type");
        };

    // Final balance of the destination purse equals to 0 as this purse is created
    // as new.
    assert_eq!(purse_secondary_balance, U512::from(0));
    assert_eq!(
        main_purse_balance,
        U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE) - *DEFAULT_PAYMENT
    );
}
