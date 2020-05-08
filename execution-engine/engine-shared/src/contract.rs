use std::collections::BTreeMap;

use types::{
    bytesrepr::{self, Error, FromBytes, ToBytes},
    Key, ProtocolVersion,
};

#[derive(PartialEq, Eq, Clone, Debug)]
pub struct ContractWasm {
    bytes: Vec<u8>,
}

impl ContractWasm {
    pub fn new(bytes: Vec<u8>) -> Self {
        ContractWasm { bytes }
    }

    pub fn take_bytes(self) -> Vec<u8> {
        self.bytes
    }

    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }
}

impl ToBytes for ContractWasm {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = bytesrepr::allocate_buffer(self)?;
        result.append(&mut self.bytes.to_bytes()?);
        Ok(result)
    }

    fn serialized_length(&self) -> usize {
        self.bytes.serialized_length()
    }
}

impl FromBytes for ContractWasm {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (bytes, rem1) = Vec::<u8>::from_bytes(bytes)?;
        Ok((ContractWasm { bytes }, rem1))
    }
}

pub mod gens {
    use proptest::{collection::vec, prelude::*};

    use types::gens::{named_keys_arb, protocol_version_arb};

    use super::ContractWasm;

    pub fn contract_arb() -> impl Strategy<Value = ContractWasm> {
        vec(any::<u8>(), 1..1000).prop_map(move |body| ContractWasm::new(body))
    }
}
