use std::convert::TryFrom;

use engine_core::engine_state::CONV_RATE;
use engine_shared::motes::Motes;
use engine_test_support::{
    internal::{
        utils, DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder,
        DEFAULT_ACCOUNT_KEY, DEFAULT_RUN_GENESIS_REQUEST,
    },
    DEFAULT_ACCOUNT_ADDR,
};
use types::{account::PublicKey, bytesrepr::ToBytes, CLValue, Key, U512};

const ACCOUNT_1_ADDR: PublicKey = PublicKey::ed25519_from([42u8; 32]);
const DO_NOTHING_WASM: &str = "do_nothing.wasm";
const TRANSFER_PURSE_TO_ACCOUNT_WASM: &str = "transfer_purse_to_account.wasm";
const TRANSFER_MAIN_PURSE_TO_NEW_PURSE_WASM: &str = "transfer_main_purse_to_new_purse.wasm";
const NAMED_PURSE_PAYMENT_WASM: &str = "named_purse_payment.wasm";

#[ignore]
#[test]
fn should_charge_non_main_purse() {
    // as account_1, create & fund a new purse and use that to pay for something
    // instead of account_1 main purse
    const TEST_PURSE_NAME: &str = "test-purse";

    let account_1_public_key = ACCOUNT_1_ADDR;
    let payment_purse_amount = U512::from(10_000_000);
    let account_1_funding_amount = U512::from(100_000_000);
    let account_1_purse_funding_amount = U512::from(50_000_000);

    let mut builder = InMemoryWasmTestBuilder::default();

    let setup_exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(
                TRANSFER_PURSE_TO_ACCOUNT_WASM, // creates account_1
                (account_1_public_key, account_1_funding_amount),
            )
            .with_empty_payment_bytes((payment_purse_amount,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([1; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let create_purse_exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_session_code(
                TRANSFER_MAIN_PURSE_TO_NEW_PURSE_WASM, // creates test purse
                (TEST_PURSE_NAME, account_1_purse_funding_amount),
            )
            .with_empty_payment_bytes((payment_purse_amount,))
            .with_authorization_keys(&[account_1_public_key])
            .with_deploy_hash([2; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let transfer_result = builder
        .run_genesis(&DEFAULT_RUN_GENESIS_REQUEST)
        .exec(setup_exec_request)
        .expect_success()
        .commit()
        .exec(create_purse_exec_request)
        .expect_success()
        .commit()
        .finish();

    // get account_1
    let account_1 = transfer_result
        .builder()
        .get_account(ACCOUNT_1_ADDR)
        .expect("should have account");
    // get purse
    let purse_key = account_1.named_keys()[TEST_PURSE_NAME];
    let purse = purse_key.into_uref().expect("should have uref");

    let purse_starting_balance = {
        let purse_bytes = purse
            .addr()
            .to_bytes()
            .expect("should be able to serialize purse bytes");

        let mint = builder.get_mint_contract_uref();
        let balance_mapping_key = Key::local(mint.addr(), &purse_bytes);
        let balance_uref = builder
            .query(None, balance_mapping_key, &[])
            .and_then(|v| CLValue::try_from(v).map_err(|error| format!("{:?}", error)))
            .and_then(|cl_value| cl_value.into_t().map_err(|error| format!("{:?}", error)))
            .expect("should find balance uref");

        let balance: U512 = builder
            .query(None, balance_uref, &[])
            .and_then(|v| CLValue::try_from(v).map_err(|error| format!("{:?}", error)))
            .and_then(|cl_value| cl_value.into_t().map_err(|error| format!("{:?}", error)))
            .expect("should parse balance into a U512");

        balance
    };

    assert_eq!(
        purse_starting_balance, account_1_purse_funding_amount,
        "purse should be funded with expected amount"
    );

    // should be able to pay for exec using new purse
    let account_payment_exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_session_code(DO_NOTHING_WASM, ())
            .with_payment_code(
                NAMED_PURSE_PAYMENT_WASM,
                (TEST_PURSE_NAME, payment_purse_amount),
            )
            .with_authorization_keys(&[account_1_public_key])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let transfer_result = builder
        .exec(account_payment_exec_request)
        .expect_success()
        .commit()
        .finish();

    let response = transfer_result
        .builder()
        .get_exec_response(2)
        .expect("there should be a response")
        .clone();

    let result = utils::get_success_result(&response);
    let gas = result.cost();
    let motes = Motes::from_gas(gas, CONV_RATE).expect("should have motes");

    let expected_resting_balance = account_1_purse_funding_amount - motes.value();

    let purse_final_balance = {
        let purse_bytes = purse
            .addr()
            .to_bytes()
            .expect("should be able to serialize purse bytes");

        let mint = builder.get_mint_contract_uref();
        let balance_mapping_key = Key::local(mint.addr(), &purse_bytes);
        let balance_uref = builder
            .query(None, balance_mapping_key, &[])
            .and_then(|v| CLValue::try_from(v).map_err(|error| format!("{:?}", error)))
            .and_then(|cl_value| cl_value.into_t().map_err(|error| format!("{:?}", error)))
            .expect("should find balance uref");

        let balance: U512 = builder
            .query(None, balance_uref, &[])
            .and_then(|v| CLValue::try_from(v).map_err(|error| format!("{:?}", error)))
            .and_then(|cl_value| cl_value.into_t().map_err(|error| format!("{:?}", error)))
            .expect("should parse balance into a U512");

        balance
    };

    assert_eq!(
        purse_final_balance, expected_resting_balance,
        "purse resting balance should equal funding amount minus exec costs"
    );
}
