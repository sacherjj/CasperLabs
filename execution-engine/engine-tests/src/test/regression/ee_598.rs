use lazy_static::lazy_static;

use engine_core::engine_state::genesis::GenesisAccount;
use engine_shared::motes::Motes;
use engine_test_support::low_level::{
    utils, DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNTS,
    DEFAULT_ACCOUNT_ADDR, DEFAULT_PAYMENT, STANDARD_PAYMENT_CONTRACT,
};
use types::{account::PublicKey, ApiError, U512};

const CONTRACT_POS_BONDING: &str = "pos_bonding.wasm";
const ACCOUNT_1_ADDR: [u8; 32] = [7u8; 32];

const GENESIS_VALIDATOR_STAKE: u64 = 50_000;
lazy_static! {
    static ref ACCOUNT_1_FUND: U512 = *DEFAULT_PAYMENT;
    static ref ACCOUNT_1_BALANCE: U512 = *ACCOUNT_1_FUND + 100_000;
    static ref ACCOUNT_1_BOND: U512 = 25_000.into();
}

#[ignore]
#[test]
fn should_fail_unboding_more_than_it_was_staked_ee_598_regression() {
    let accounts = {
        let mut tmp: Vec<GenesisAccount> = DEFAULT_ACCOUNTS.clone();
        let account = GenesisAccount::new(
            PublicKey::new([42; 32]),
            Motes::new(GENESIS_VALIDATOR_STAKE.into()) * Motes::new(2.into()),
            Motes::new(GENESIS_VALIDATOR_STAKE.into()),
        );
        tmp.push(account);
        tmp
    };

    let genesis_config = utils::create_genesis_config(accounts);

    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_POS_BONDING,
        (
            String::from("seed_new_account"),
            PublicKey::new(ACCOUNT_1_ADDR),
            *ACCOUNT_1_BALANCE,
        ),
    )
    .build();
    let exec_request_2 = {
        let deploy = DeployItemBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (*ACCOUNT_1_FUND,))
            .with_session_code("ee_598_regression.wasm", (*ACCOUNT_1_BOND,))
            .with_deploy_hash([2u8; 32])
            .with_authorization_keys(&[PublicKey::new(ACCOUNT_1_ADDR)])
            .build();
        ExecuteRequestBuilder::from_deploy_item(deploy).build()
    };

    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&genesis_config)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .exec(exec_request_2)
        .commit()
        .finish();

    let response = result
        .builder()
        .get_exec_response(1)
        .expect("should have a response")
        .to_owned();
    let error_message = utils::get_error_message(response);
    // Error::UnbondTooLarge => 7,
    assert!(error_message.contains(&format!("Revert({})", u32::from(ApiError::ProofOfStake(7)))));
}
