use std::rc::Rc;

use engine_core::engine_state::{
    execution_result::ExecutionResult,
    genesis::{GenesisAccount, POS_REWARDS_PURSE},
    CONV_RATE,
};
use engine_shared::motes::Motes;
use engine_test_support::low_level::{
    utils, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNTS, DEFAULT_ACCOUNT_ADDR,
};
use types::{
    account::{PublicKey, PurseId},
    Key, U512,
};

const CONTRACT_DO_NOTHING: &str = "do_nothing.wasm";
const CONTRACT_TRANSFER: &str = "transfer_purse_to_account.wasm";
const CONTRACT_EE_803_REGRESSION: &str = "ee_803_regression.wasm";
const COMMAND_BOND: &str = "bond";
const COMMAND_UNBOND: &str = "unbond";
const ACCOUNT_ADDR_1: [u8; 32] = [1u8; 32];
const GENESIS_VALIDATOR_STAKE: u64 = 50_000;

fn get_pos_purse_id_by_name(
    builder: &InMemoryWasmTestBuilder,
    purse_name: &str,
) -> Option<PurseId> {
    let pos_contract = builder.get_pos_contract();

    pos_contract
        .named_keys()
        .get(purse_name)
        .and_then(Key::as_uref)
        .map(|u| PurseId::new(*u))
}

fn get_cost(response: &[Rc<ExecutionResult>]) -> U512 {
    let motes = Motes::from_gas(
        utils::get_exec_costs(response)
            .into_iter()
            .fold(Default::default(), |i, acc| i + acc),
        CONV_RATE,
    )
    .expect("should convert");
    motes.value()
}

// TODO: should be made more granular when unignored - right now it is meant to demonstrate the
// issue, but once the underlying problem is fixed, the procedure should probably fail at the
// bonding step and we should be asserting that
#[test]
#[ignore]
#[should_panic]
fn should_not_be_able_to_unbond_reward() {
    let mut builder = InMemoryWasmTestBuilder::default();

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
    builder.run_genesis(&genesis_config);

    // First request to put some funds in the reward purse
    let exec_request_0 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_DO_NOTHING, ()).build();

    builder.exec(exec_request_0).expect_success().commit();

    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER,
        (ACCOUNT_ADDR_1, U512::from(100)),
    )
    .build();

    builder.exec(exec_request_1).expect_success().commit();

    let rewards_purse = get_pos_purse_id_by_name(&builder, POS_REWARDS_PURSE).unwrap();
    let default_account_purse = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get genesis account")
        .purse_id();

    let rewards_balance_pre = builder.get_purse_balance(rewards_purse);
    let default_acc_balance_pre = builder.get_purse_balance(default_account_purse);
    let amount_to_steal = U512::from(100_000);

    // try to bond using the funds from the rewards purse (should be illegal)

    let exec_request_2 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_EE_803_REGRESSION,
        (COMMAND_BOND, rewards_purse, amount_to_steal),
    )
    .build();

    let response_2 = builder
        .exec(exec_request_2)
        .expect_success()
        .commit()
        .get_exec_response(2)
        .expect("there should be a response")
        .to_owned();

    // try to unbond, thus transferring the funds originally taken from the rewards purse to a
    // user's account

    let exec_request_3 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_EE_803_REGRESSION,
        (COMMAND_UNBOND,),
    )
    .build();

    let response_3 = builder
        .exec(exec_request_3)
        .expect_success()
        .commit()
        .get_exec_response(3)
        .expect("there should be a response")
        .to_owned();

    let rewards_balance_post = builder.get_purse_balance(rewards_purse);
    let default_acc_balance_post = builder.get_purse_balance(default_account_purse);

    // check that the funds have actually been stolen

    let exec_2_cost = get_cost(&response_2);
    let exec_3_cost = get_cost(&response_3);

    assert_eq!(
        rewards_balance_post,
        rewards_balance_pre + exec_2_cost + exec_3_cost - amount_to_steal
    );
    assert_eq!(
        default_acc_balance_post,
        default_acc_balance_pre - exec_2_cost - exec_3_cost + amount_to_steal
    );
}
