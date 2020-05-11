use types::contracts::Contract;

use super::NamedKeyMap;
use crate::engine_server::state::{self, NamedKey};

impl From<Contract> for state::Contract {
    fn from(contract: Contract) -> Self {
        let mut pb_contract = state::Contract::new();
        let named_keys: Vec<NamedKey> = NamedKeyMap::new(contract.take_named_keys()).into();
        pb_contract.set_named_keys(named_keys.into());
        pb_contract.set_protocol_version(contract.protocol_version().into());
        pb_contract
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
