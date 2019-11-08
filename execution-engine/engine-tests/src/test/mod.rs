#[cfg(test)]
mod metrics;
#[cfg(test)]
mod upgrade;

#[cfg(test)]
pub mod contract_api;
#[cfg(test)]
pub mod deploy;
#[cfg(test)]
pub mod regression;
#[cfg(test)]
pub mod system_contracts;

use lazy_static::lazy_static;
use num_traits::identities::Zero;

use contract_ffi::value::{account::PublicKey, ProtocolVersion, U512};
use engine_core::engine_state::genesis::{GenesisAccount, GenesisConfig};
use engine_shared::{motes::Motes, test_utils};
use engine_wasm_prep::wasm_costs::WasmCosts;

use crate::support::test_support;

pub const DEFAULT_CHAIN_NAME: &str = "gerald";
pub const DEFAULT_GENESIS_TIMESTAMP: u64 = 0;
pub const DEFAULT_ACCOUNT_ADDR: [u8; 32] = [6u8; 32];
pub const DEFAULT_ACCOUNT_INITIAL_BALANCE: u64 = 100_000_000_000;

pub const CONTRACT_MINT_INSTALL: &str = "mint_install.wasm";
pub const CONTRACT_POS_INSTALL: &str = "pos_install.wasm";
pub const CONTRACT_STANDARD_PAYMENT: &str = "standard_payment.wasm";

lazy_static! {
    pub static ref DEFAULT_ACCOUNT_KEY: PublicKey = PublicKey::new(DEFAULT_ACCOUNT_ADDR);
    pub static ref DEFAULT_ACCOUNTS: Vec<GenesisAccount> = {
        let mut ret = Vec::new();
        let genesis_account = GenesisAccount::new(
            PublicKey::new(DEFAULT_ACCOUNT_ADDR),
            Motes::new(DEFAULT_ACCOUNT_INITIAL_BALANCE.into()),
            Motes::zero(),
        );
        ret.push(genesis_account);
        ret
    };
    pub static ref DEFAULT_PROTOCOL_VERSION: ProtocolVersion = ProtocolVersion::V1_0_0;
    pub static ref DEFAULT_PAYMENT: U512 = 100_000_000.into();
    pub static ref DEFAULT_WASM_COSTS: WasmCosts = test_utils::wasm_costs_mock();
    pub static ref DEFAULT_GENESIS_CONFIG: GenesisConfig = {
        let mint_installer_bytes = test_support::read_wasm_file_bytes(CONTRACT_MINT_INSTALL);
        let pos_installer_bytes = test_support::read_wasm_file_bytes(CONTRACT_POS_INSTALL);
        GenesisConfig::new(
            DEFAULT_CHAIN_NAME.to_string(),
            DEFAULT_GENESIS_TIMESTAMP,
            *DEFAULT_PROTOCOL_VERSION,
            mint_installer_bytes,
            pos_installer_bytes,
            DEFAULT_ACCOUNTS.clone(),
            *DEFAULT_WASM_COSTS,
        )
    };
}
