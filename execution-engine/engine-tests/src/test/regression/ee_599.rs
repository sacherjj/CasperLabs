use lazy_static::lazy_static;

use contract_ffi::value::account::{PublicKey, PurseId};
use contract_ffi::value::U512;
use engine_core::engine_state::CONV_RATE;
use engine_shared::motes::Motes;

use crate::support::test_support::{self, ExecuteRequestBuilder, InMemoryWasmTestBuilder};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT};

const CONTRACT_EE_599_REGRESSION: &str = "ee_599_regression.wasm";
const CONTRACT_TRANSFER_TO_ACCOUNT: &str = "transfer_to_account.wasm";
const DONATION_BOX_COPY_KEY: &str = "donation_box_copy";
const TRANSFER_FUNDS_KEY: &str = "transfer_funds";
const DONATION_AMOUNT: u64 = 1;
const VICTIM_ADDR: [u8; 32] = [42; 32];

lazy_static! {
    static ref VICTIM_INITIAL_FUNDS: U512 = *DEFAULT_PAYMENT * 10;
}

fn setup() -> InMemoryWasmTestBuilder {
    // Creates victim account
    let exec_request_1 = {
        let args = (PublicKey::new(VICTIM_ADDR), VICTIM_INITIAL_FUNDS.as_u64());
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_TRANSFER_TO_ACCOUNT, args)
            .build()
    };

    // Deploy contract
    let exec_request_2 = {
        let args = ("install".to_string(),);
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_EE_599_REGRESSION, args)
            .build()
    };

    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .exec(exec_request_2)
        .expect_success()
        .commit()
        .finish();

    InMemoryWasmTestBuilder::from_result(result)
}

#[ignore]
#[test]
fn should_not_be_able_to_transfer_funds_with_transfer_purse_to_purse() {
    let mut builder = setup();

    let victim_account = builder
        .get_account(VICTIM_ADDR)
        .expect("should have victim account");
    let victim_purse_id = victim_account.purse_id();

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should have default account");
    let steal_funds = default_account
        .named_keys()
        .get(TRANSFER_FUNDS_KEY)
        .cloned()
        .unwrap_or_else(|| panic!("should have {}", TRANSFER_FUNDS_KEY));
    let donation_box_copy_key = default_account
        .named_keys()
        .get(DONATION_BOX_COPY_KEY)
        .cloned()
        .unwrap_or_else(|| panic!("should have {}", DONATION_BOX_COPY_KEY));

    let donation_box_copy_uref = donation_box_copy_key.as_uref().expect("should be uref");
    let donation_box_copy = PurseId::new(*donation_box_copy_uref);

    let exec_request_3 = {
        let args = (
            "call".to_string(),
            steal_funds,
            "transfer_from_purse_to_purse",
        );
        ExecuteRequestBuilder::standard(VICTIM_ADDR, CONTRACT_EE_599_REGRESSION, args).build()
    };

    let result_2 = builder
        .exec(exec_request_3)
        .expect_success()
        .commit()
        .finish();

    let victim_balance = result_2.builder().get_purse_balance(victim_purse_id);

    let exec_3_response = result_2
        .builder()
        .get_exec_response(0)
        .expect("should have response");

    let gas_cost = Motes::from_gas(test_support::get_exec_costs(&exec_3_response)[0], CONV_RATE)
        .expect("should convert");

    assert_eq!(
        victim_balance,
        *VICTIM_INITIAL_FUNDS - gas_cost.value() - DONATION_AMOUNT,
    );

    assert_eq!(
        result_2.builder().get_purse_balance(donation_box_copy),
        U512::from(DONATION_AMOUNT),
    );
}

#[ignore]
#[test]
fn should_not_be_able_to_transfer_funds_with_transfer_from_purse_to_account() {
    let mut builder = setup();

    let victim_account = builder
        .get_account(VICTIM_ADDR)
        .expect("should have victim account");

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should have default account");

    let default_account_balance = builder.get_purse_balance(default_account.purse_id());

    let steal_funds = default_account
        .named_keys()
        .get(TRANSFER_FUNDS_KEY)
        .cloned()
        .unwrap_or_else(|| panic!("should have {}", TRANSFER_FUNDS_KEY));
    let donation_box_copy_key = default_account
        .named_keys()
        .get(DONATION_BOX_COPY_KEY)
        .cloned()
        .unwrap_or_else(|| panic!("should have {}", DONATION_BOX_COPY_KEY));

    let donation_box_copy_uref = donation_box_copy_key.as_uref().expect("should be uref");
    let donation_box_copy = PurseId::new(*donation_box_copy_uref);

    let exec_request_3 = {
        let args = (
            "call".to_string(),
            steal_funds,
            "transfer_from_purse_to_account",
        );
        ExecuteRequestBuilder::standard(VICTIM_ADDR, CONTRACT_EE_599_REGRESSION, args).build()
    };

    let result_2 = builder
        .exec(exec_request_3)
        .expect_success()
        .commit()
        .finish();

    let victim_balance = result_2
        .builder()
        .get_purse_balance(victim_account.purse_id());

    let exec_3_response = result_2
        .builder()
        .get_exec_response(0)
        .expect("should have response");

    let gas_cost = Motes::from_gas(test_support::get_exec_costs(&exec_3_response)[0], CONV_RATE)
        .expect("should convert");

    assert_eq!(
        victim_balance,
        *VICTIM_INITIAL_FUNDS - gas_cost.value() - DONATION_AMOUNT,
    );

    // In this variant of test `donation_box` is left unchanged i.e. zero balance
    assert_eq!(
        result_2.builder().get_purse_balance(donation_box_copy),
        U512::zero(),
    );

    // Instead, main purse of the 'contract maintainer' is updated
    let updated_default_account_balance = result_2
        .builder()
        .get_purse_balance(default_account.purse_id());

    assert_eq!(
        updated_default_account_balance - default_account_balance,
        U512::from(DONATION_AMOUNT),
    )
}

#[ignore]
#[test]
fn should_not_be_able_to_transfer_funds_with_transfer_to_account() {
    let mut builder = setup();

    let victim_account = builder
        .get_account(VICTIM_ADDR)
        .expect("should have victim account");

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should have default account");

    let default_account_balance = builder.get_purse_balance(default_account.purse_id());

    let steal_funds = default_account
        .named_keys()
        .get(TRANSFER_FUNDS_KEY)
        .cloned()
        .unwrap_or_else(|| panic!("should have {}", TRANSFER_FUNDS_KEY));
    let donation_box_copy_key = default_account
        .named_keys()
        .get(DONATION_BOX_COPY_KEY)
        .cloned()
        .unwrap_or_else(|| panic!("should have {}", DONATION_BOX_COPY_KEY));

    let donation_box_copy_uref = donation_box_copy_key.as_uref().expect("should be uref");
    let donation_box_copy = PurseId::new(*donation_box_copy_uref);

    let exec_request_3 = {
        let args = ("call".to_string(), steal_funds, "transfer_to_account");
        ExecuteRequestBuilder::standard(VICTIM_ADDR, CONTRACT_EE_599_REGRESSION, args).build()
    };

    let result_2 = builder
        .exec(exec_request_3)
        .expect_success()
        .commit()
        .finish();

    let victim_balance = result_2
        .builder()
        .get_purse_balance(victim_account.purse_id());

    let exec_3_response = result_2
        .builder()
        .get_exec_response(0)
        .expect("should have response");

    let gas_cost = Motes::from_gas(test_support::get_exec_costs(&exec_3_response)[0], CONV_RATE)
        .expect("should convert");

    assert_eq!(
        victim_balance,
        *VICTIM_INITIAL_FUNDS - gas_cost.value() - DONATION_AMOUNT,
    );

    // In this variant of test `donation_box` is left unchanged i.e. zero balance
    assert_eq!(
        result_2.builder().get_purse_balance(donation_box_copy),
        U512::zero(),
    );

    // Instead, main purse of the 'contract maintainer' is updated
    let updated_default_account_balance = result_2
        .builder()
        .get_purse_balance(default_account.purse_id());

    assert_eq!(
        updated_default_account_balance - default_account_balance,
        U512::from(DONATION_AMOUNT),
    )
}
