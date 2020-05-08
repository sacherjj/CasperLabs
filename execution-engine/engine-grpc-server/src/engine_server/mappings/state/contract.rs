use std::convert::{TryFrom, TryInto};

use engine_shared::contract::ContractWasm;

use super::NamedKeyMap;
use crate::engine_server::{
    mappings::ParsingError,
    state::{self, NamedKey},
};

impl From<ContractWasm> for state::Contract {
    fn from(contract: ContractWasm) -> Self {
        let (bytes, named_keys, protocol_version) = contract.destructure();
        let mut pb_contract = state::Contract::new();
        let named_keys: Vec<NamedKey> = NamedKeyMap::new(named_keys).into();
        pb_contract.set_body(bytes);
        pb_contract.set_named_keys(named_keys.into());
        pb_contract.set_protocol_version(protocol_version.into());
        pb_contract
    }
}

impl TryFrom<state::Contract> for ContractWasm {
    type Error = ParsingError;

    fn try_from(mut pb_contract: state::Contract) -> Result<Self, Self::Error> {
        let named_keys: NamedKeyMap = pb_contract.take_named_keys().into_vec().try_into()?;
        let protocol_version = pb_contract.take_protocol_version().into();
        let contract =
            ContractWasm::new(pb_contract.body, named_keys.into_inner(), protocol_version);
        Ok(contract)
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
            test_utils::protobuf_round_trip::<ContractWasm, state::Contract>(contract);
        }
    }
}
