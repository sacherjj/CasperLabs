use std::convert::{TryFrom, TryInto};

use contract_ffi::value::Contract;

use super::NamedKeyMap;
use crate::engine_server::{
    mappings::ParsingError,
    state::{Contract as ProtobufContract, NamedKey as ProtobufNamedKey},
};

impl From<Contract> for ProtobufContract {
    fn from(contract: Contract) -> Self {
        let (bytes, named_keys, protocol_version) = contract.destructure();
        let mut pb_contract = ProtobufContract::new();
        let named_keys: Vec<ProtobufNamedKey> = NamedKeyMap(named_keys).into();
        pb_contract.set_body(bytes);
        pb_contract.set_named_keys(named_keys.into());
        pb_contract.set_protocol_version(protocol_version.into());
        pb_contract
    }
}

impl TryFrom<ProtobufContract> for Contract {
    type Error = ParsingError;

    fn try_from(mut pb_contract: ProtobufContract) -> Result<Self, Self::Error> {
        let named_keys: NamedKeyMap = pb_contract.take_named_keys().into_vec().try_into()?;
        let protocol_version = pb_contract.take_protocol_version().into();
        let contract = Contract::new(pb_contract.body, named_keys.0, protocol_version);
        Ok(contract)
    }
}

#[cfg(test)]
mod tests {
    use proptest::proptest;

    use contract_ffi::gens;

    use super::*;
    use crate::engine_server::mappings::test_utils;

    proptest! {
        #[test]
        fn round_trip(contract in gens::contract_arb()) {
            test_utils::protobuf_round_trip::<Contract, ProtobufContract>(contract);
        }
    }
}
