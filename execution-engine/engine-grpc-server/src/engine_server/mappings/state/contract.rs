use types::contracts::{Contract, NamedKeys};

use super::NamedKeyMap;
use crate::engine_server::{mappings::ParsingError, state};
use std::convert::{TryFrom, TryInto};

impl From<Contract> for state::Contract {
    fn from(contract: Contract) -> Self {
        let (contract_package_hash, contract_wasm_hash, named_keys, entry_points, protocol_version) =
            contract.into();
        let mut pb_contract = state::Contract::new();
        let named_keys: Vec<state::NamedKey> = NamedKeyMap::new(named_keys).into();
        let entry_points: Vec<state::Contract_EntryPoint> = entry_points
            .take_entry_points()
            .into_iter()
            .map(Into::into)
            .collect();
        pb_contract.set_contract_package_hash(contract_package_hash.to_vec());
        pb_contract.set_contract_wasm_hash(contract_wasm_hash.to_vec());
        pb_contract.set_named_keys(named_keys.into());
        pb_contract.set_entry_points(entry_points.into());
        pb_contract.set_protocol_version(protocol_version.into());
        pb_contract
    }
}

impl TryFrom<state::Contract> for Contract {
    type Error = ParsingError;
    fn try_from(mut value: state::Contract) -> Result<Self, Self::Error> {
        let named_keys = {
            let mut named_keys = NamedKeys::new();
            for mut named_key in value.take_named_keys().into_iter() {
                named_keys.insert(named_key.take_name(), named_key.take_key().try_into()?);
            }
            named_keys
        };
        Ok(Contract::new(
            [0; 32],
            [0; 32],
            named_keys,
            Default::default(),
            Default::default(),
        ))
    }
}

#[cfg(test)]
mod tests {
    use proptest::proptest;

    use engine_shared::contract::gens;

    use super::*;
    use crate::engine_server::mappings::test_utils;

    proptest! {
        #[test]
        fn round_trip(contract in gens::contract_arb()) {
            test_utils::protobuf_round_trip::<Contract, state::Contract>(contract);
        }
    }
}
