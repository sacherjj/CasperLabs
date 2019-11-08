use std::convert::{TryFrom, TryInto};

use engine_core::engine_state::genesis::{GenesisAccount, GenesisConfig};

use crate::engine_server::{
    ipc::{
        ChainSpec_GenesisAccount as ProtobufGenesisAccount,
        ChainSpec_GenesisConfig as ProtobufGenesisConfig,
    },
    mappings::MappingError,
};

impl From<GenesisConfig> for ProtobufGenesisConfig {
    fn from(genesis_config: GenesisConfig) -> Self {
        let mut pb_genesis_config = ProtobufGenesisConfig::new();

        pb_genesis_config.set_name(genesis_config.name().to_string());
        pb_genesis_config.set_timestamp(genesis_config.timestamp());
        pb_genesis_config.set_protocol_version(genesis_config.protocol_version().into());
        pb_genesis_config.set_mint_installer(genesis_config.mint_installer_bytes().to_vec());
        pb_genesis_config
            .set_pos_installer(genesis_config.proof_of_stake_installer_bytes().to_vec());
        {
            let accounts = genesis_config
                .accounts()
                .iter()
                .cloned()
                .map(Into::into)
                .collect::<Vec<ProtobufGenesisAccount>>();
            pb_genesis_config.set_accounts(accounts.into());
        }
        pb_genesis_config
            .mut_costs()
            .set_wasm(genesis_config.wasm_costs().into());
        pb_genesis_config
    }
}

impl TryFrom<ProtobufGenesisConfig> for GenesisConfig {
    type Error = MappingError;

    fn try_from(mut pb_genesis_config: ProtobufGenesisConfig) -> Result<Self, Self::Error> {
        let name = pb_genesis_config.take_name();
        let timestamp = pb_genesis_config.get_timestamp();
        let protocol_version = pb_genesis_config.take_protocol_version().into();
        let accounts = pb_genesis_config
            .take_accounts()
            .into_iter()
            .map(TryInto::try_into)
            .collect::<Result<Vec<GenesisAccount>, Self::Error>>()?;
        let wasm_costs = pb_genesis_config.take_costs().take_wasm().into();
        let mint_initializer_bytes = pb_genesis_config.mint_installer;
        let proof_of_stake_initializer_bytes = pb_genesis_config.pos_installer;
        Ok(GenesisConfig::new(
            name,
            timestamp,
            protocol_version,
            mint_initializer_bytes,
            proof_of_stake_initializer_bytes,
            accounts,
            wasm_costs,
        ))
    }
}

#[cfg(test)]
mod tests {
    use rand;

    use super::*;
    use crate::engine_server::mappings::test_utils;

    #[test]
    fn round_trip() {
        let genesis_config = rand::random();
        test_utils::protobuf_round_trip::<GenesisConfig, ProtobufGenesisConfig>(genesis_config);
    }
}
