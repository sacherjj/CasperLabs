use std::convert::{TryFrom, TryInto};

use engine_core::engine_state::genesis::GenesisAccount;
use engine_shared::motes::Motes;
use types::account::PublicKey;

use crate::engine_server::{
    ipc::ChainSpec_GenesisConfig_ExecConfig_GenesisAccount, mappings::MappingError,
};

impl From<GenesisAccount> for ChainSpec_GenesisConfig_ExecConfig_GenesisAccount {
    fn from(genesis_account: GenesisAccount) -> Self {
        let mut pb_genesis_account = ChainSpec_GenesisConfig_ExecConfig_GenesisAccount::new();

        pb_genesis_account.set_public_key(genesis_account.public_key().as_bytes().to_vec());
        pb_genesis_account.set_balance(genesis_account.balance().value().into());
        pb_genesis_account.set_bonded_amount(genesis_account.bonded_amount().value().into());

        pb_genesis_account
    }
}

impl TryFrom<ChainSpec_GenesisConfig_ExecConfig_GenesisAccount> for GenesisAccount {
    type Error = MappingError;

    fn try_from(
        mut pb_genesis_account: ChainSpec_GenesisConfig_ExecConfig_GenesisAccount,
    ) -> Result<Self, Self::Error> {
        // TODO: our TryFromSliceForPublicKeyError should convey length info
        let public_key =
            PublicKey::ed25519_try_from(pb_genesis_account.get_public_key()).map_err(|_| {
                MappingError::invalid_public_key_length(pb_genesis_account.public_key.len())
            })?;
        let balance = pb_genesis_account
            .take_balance()
            .try_into()
            .map(Motes::new)?;
        let bonded_amount = pb_genesis_account
            .take_bonded_amount()
            .try_into()
            .map(Motes::new)?;
        Ok(GenesisAccount::new(public_key, balance, bonded_amount))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine_server::mappings::test_utils;

    #[test]
    fn round_trip() {
        let genesis_account = rand::random();
        test_utils::protobuf_round_trip::<
            GenesisAccount,
            ChainSpec_GenesisConfig_ExecConfig_GenesisAccount,
        >(genesis_account);
    }
}
