use contract_ffi::{
    key::Key,
    value::{
        account::{PublicKey, PurseId},
        U512,
    },
};
use engine_core::engine_state::genesis::{GenesisAccount, POS_REWARDS_PURSE};
use engine_shared::motes::Motes;

use crate::{
    support::test_support::{self, ExecuteRequestBuilder, InMemoryWasmTestBuilder},
    test::{DEFAULT_ACCOUNTS, DEFAULT_ACCOUNT_ADDR},
};

const CONTRACT_TRANSFER: &str = "transfer_purse_to_account.wasm";
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

#[test]
#[ignore]
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

    let genesis_config = test_support::create_genesis_config(accounts);
    builder.run_genesis(&genesis_config);

    // First request to put some funds in the reward purse
    let exec_request_0 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, "do_nothing.wasm", ()).build();

    builder.exec(exec_request_0).expect_success().commit();

    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER,
        (ACCOUNT_ADDR_1, U512::from(100)),
    )
    .build();

    builder.exec(exec_request_1).expect_success().commit();

    let rewards_purse = get_pos_purse_id_by_name(&builder, POS_REWARDS_PURSE).unwrap();

    let amount_to_steal = U512::from(100_000);

    let exec_request_2 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        "ee_803_regression.wasm",
        ("bond", rewards_purse, amount_to_steal),
    )
    .build();

    builder.exec(exec_request_2).expect_success().commit();

    let exec_request_3 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        "ee_803_regression.wasm",
        ("unbond",),
    )
    .build();

    builder.exec(exec_request_3).expect_success().commit();
}
