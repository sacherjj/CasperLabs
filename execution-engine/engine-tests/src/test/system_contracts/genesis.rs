use engine_core::engine_state::{
    genesis::{GenesisAccount, GenesisConfig},
    SYSTEM_ACCOUNT_ADDR,
};
use engine_shared::{motes::Motes, stored_value::StoredValue};
use engine_test_support::low_level::{utils, InMemoryWasmTestBuilder, DEFAULT_WASM_COSTS};
use types::{account::PublicKey, Key, ProtocolVersion, U512};

const MINT_INSTALL: &str = "mint_install.wasm";
const POS_INSTALL: &str = "pos_install.wasm";
const BAD_INSTALL: &str = "standard_payment.wasm";

const CHAIN_NAME: &str = "Jeremiah";
const TIMESTAMP: u64 = 0;
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_2_ADDR: [u8; 32] = [2u8; 32];
const ACCOUNT_1_BONDED_AMOUNT: u64 = 1_000_000;
const ACCOUNT_2_BONDED_AMOUNT: u64 = 2_000_000;
const ACCOUNT_1_BALANCE: u64 = 1_000_000_000;
const ACCOUNT_2_BALANCE: u64 = 2_000_000_000;

#[ignore]
#[test]
fn should_run_genesis() {
    let account_1_balance = Motes::new(ACCOUNT_1_BALANCE.into());
    let account_1 = {
        let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
        let account_1_bonded_amount = Motes::new(ACCOUNT_1_BONDED_AMOUNT.into());
        GenesisAccount::new(
            account_1_public_key,
            account_1_balance,
            account_1_bonded_amount,
        )
    };

    let account_2_balance = Motes::new(ACCOUNT_2_BALANCE.into());
    let account_2 = {
        let account_2_public_key = PublicKey::new(ACCOUNT_2_ADDR);
        let account_2_bonded_amount = Motes::new(ACCOUNT_2_BONDED_AMOUNT.into());
        GenesisAccount::new(
            account_2_public_key,
            account_2_balance,
            account_2_bonded_amount,
        )
    };

    let name = CHAIN_NAME.to_string();
    let mint_installer_bytes = utils::read_wasm_file_bytes(MINT_INSTALL);
    let pos_installer_bytes = utils::read_wasm_file_bytes(POS_INSTALL);
    let accounts = vec![account_1, account_2];
    let protocol_version = ProtocolVersion::V1_0_0;
    let wasm_costs = *DEFAULT_WASM_COSTS;

    let genesis_config = GenesisConfig::new(
        name,
        TIMESTAMP,
        protocol_version,
        mint_installer_bytes,
        pos_installer_bytes,
        accounts,
        wasm_costs,
    );

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&genesis_config);

    let system_account = builder
        .get_account(SYSTEM_ACCOUNT_ADDR)
        .expect("system account should exist");

    let account_1 = builder
        .get_account(ACCOUNT_1_ADDR)
        .expect("account 1 should exist");

    let account_2 = builder
        .get_account(ACCOUNT_2_ADDR)
        .expect("account 2 should exist");

    let system_account_balance_actual = builder.get_purse_balance(system_account.purse_id());
    let account_1_balance_actual = builder.get_purse_balance(account_1.purse_id());
    let account_2_balance_actual = builder.get_purse_balance(account_2.purse_id());

    assert_eq!(system_account_balance_actual, U512::zero());
    assert_eq!(account_1_balance_actual, account_1_balance.value());
    assert_eq!(account_2_balance_actual, account_2_balance.value());

    let mint_contract_uref = builder.get_mint_contract_uref();
    let pos_contract_uref = builder.get_pos_contract_uref();

    if let Some(StoredValue::Contract(_)) = builder.query(None, Key::URef(mint_contract_uref), &[])
    {
        // Contract exists at mint contract URef
    } else {
        panic!("contract not found at mint uref");
    }

    if let Some(StoredValue::Contract(_)) = builder.query(None, Key::URef(pos_contract_uref), &[]) {
        // Contract exists at pos contract URef
    } else {
        panic!("contract not found at pos uref");
    }
}

#[ignore]
#[should_panic]
#[test]
fn should_fail_if_bad_mint_install_contract_is_provided() {
    let genesis_config = {
        let account_1 = {
            let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
            let account_1_balance = Motes::new(ACCOUNT_1_BALANCE.into());
            let account_1_bonded_amount = Motes::new(ACCOUNT_1_BONDED_AMOUNT.into());
            GenesisAccount::new(
                account_1_public_key,
                account_1_balance,
                account_1_bonded_amount,
            )
        };
        let account_2 = {
            let account_2_public_key = PublicKey::new(ACCOUNT_2_ADDR);
            let account_2_balance = Motes::new(ACCOUNT_2_BALANCE.into());
            let account_2_bonded_amount = Motes::new(ACCOUNT_2_BONDED_AMOUNT.into());
            GenesisAccount::new(
                account_2_public_key,
                account_2_balance,
                account_2_bonded_amount,
            )
        };
        let name = CHAIN_NAME.to_string();
        let mint_installer_bytes = utils::read_wasm_file_bytes(BAD_INSTALL);
        let pos_installer_bytes = utils::read_wasm_file_bytes(POS_INSTALL);
        let accounts = vec![account_1, account_2];
        let protocol_version = ProtocolVersion::V1_0_0;
        let wasm_costs = *DEFAULT_WASM_COSTS;

        GenesisConfig::new(
            name,
            TIMESTAMP,
            protocol_version,
            mint_installer_bytes,
            pos_installer_bytes,
            accounts,
            wasm_costs,
        )
    };

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&genesis_config);
}

#[ignore]
#[should_panic]
#[test]
fn should_fail_if_bad_pos_install_contract_is_provided() {
    let genesis_config = {
        let account_1 = {
            let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
            let account_1_balance = Motes::new(ACCOUNT_1_BALANCE.into());
            let account_1_bonded_amount = Motes::new(ACCOUNT_1_BONDED_AMOUNT.into());
            GenesisAccount::new(
                account_1_public_key,
                account_1_balance,
                account_1_bonded_amount,
            )
        };
        let account_2 = {
            let account_2_public_key = PublicKey::new(ACCOUNT_2_ADDR);
            let account_2_balance = Motes::new(ACCOUNT_2_BALANCE.into());
            let account_2_bonded_amount = Motes::new(ACCOUNT_2_BONDED_AMOUNT.into());
            GenesisAccount::new(
                account_2_public_key,
                account_2_balance,
                account_2_bonded_amount,
            )
        };
        let name = CHAIN_NAME.to_string();
        let mint_installer_bytes = utils::read_wasm_file_bytes(MINT_INSTALL);
        let pos_installer_bytes = utils::read_wasm_file_bytes(BAD_INSTALL);
        let accounts = vec![account_1, account_2];
        let protocol_version = ProtocolVersion::V1_0_0;
        let wasm_costs = *DEFAULT_WASM_COSTS;

        GenesisConfig::new(
            name,
            TIMESTAMP,
            protocol_version,
            mint_installer_bytes,
            pos_installer_bytes,
            accounts,
            wasm_costs,
        )
    };

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&genesis_config);
}
