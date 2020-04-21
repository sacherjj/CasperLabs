use engine_core::engine_state::CONV_RATE;
use engine_shared::motes::Motes;
use engine_test_support::{
    internal::{
        utils, DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder,
        DEFAULT_ACCOUNT_KEY, DEFAULT_RUN_GENESIS_REQUEST,
    },
    DEFAULT_ACCOUNT_ADDR, DEFAULT_ACCOUNT_INITIAL_BALANCE,
};
use types::{account::PublicKey, U512};

const CONTRACT_TRANSFER_TO_ACCOUNT_NAME: &str = "transfer_to_account";
const STANDARD_PAYMENT_CONTRACT_NAME: &str = "standard_payment";
const STORE_AT_HASH: &str = "hash";
const ACCOUNT_1_ADDR: PublicKey = PublicKey::ed25519_from([1u8; 32]);

#[ignore]
#[test]
fn should_transfer_to_account_stored() {
    let mut builder = InMemoryWasmTestBuilder::default();
    {
        // first, store transfer contract
        let exec_request = ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            &format!("{}_stored.wasm", CONTRACT_TRANSFER_TO_ACCOUNT_NAME),
            (STORE_AT_HASH.to_string(),),
        )
        .build();
        builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST);
        builder.exec_commit_finish(exec_request);
    }

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should have account");

    let contract_hash = default_account
        .named_keys()
        .get(CONTRACT_TRANSFER_TO_ACCOUNT_NAME)
        .expect("contract_hash should exist")
        .into_hash()
        .expect("should be a hash");

    let response = builder
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();
    let mut result = utils::get_success_result(&response);
    let gas = result.cost();
    let motes_alpha = Motes::from_gas(gas, CONV_RATE).expect("should have motes");

    let modified_balance_alpha: U512 = builder.get_purse_balance(default_account.main_purse());

    let transferred_amount: u64 = 1;
    let payment_purse_amount = 10_000_000;

    // next make another deploy that USES stored payment logic
    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_session_hash(contract_hash.to_vec(), (ACCOUNT_1_ADDR, transferred_amount))
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([2; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    builder.exec_commit_finish(exec_request);

    let modified_balance_bravo: U512 = builder.get_purse_balance(default_account.main_purse());

    let initial_balance: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);

    let response = builder
        .get_exec_response(1)
        .expect("there should be a response")
        .clone();

    result = utils::get_success_result(&response);
    let gas = result.cost();
    let motes_bravo = Motes::from_gas(gas, CONV_RATE).expect("should have motes");

    let tally = motes_alpha.value()
        + motes_bravo.value()
        + U512::from(transferred_amount)
        + modified_balance_bravo;

    assert!(
        modified_balance_alpha < initial_balance,
        "balance should be less than initial balance"
    );

    assert!(
        modified_balance_bravo < modified_balance_alpha,
        "second modified balance should be less than first modified balance"
    );

    assert_eq!(
        initial_balance, tally,
        "no net resources should be gained or lost post-distribution"
    );
}
