use std::{
    cmp,
    fmt::{Debug, Display},
};
use types::bytesrepr::{self, Error, FromBytes, ToBytes};

const CONTRACT_WASM_MAX_DISPLAY_LEN: usize = 64;

#[derive(PartialEq, Eq, Clone)]
pub struct ContractWasm {
    bytes: Vec<u8>,
}

impl Display for ContractWasm {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "0x{}", base16::encode_lower(&self.bytes))
    }
}

impl Debug for ContractWasm {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let size_limit = cmp::min(CONTRACT_WASM_MAX_DISPLAY_LEN, self.bytes.len());
        let sliced_bytes = &self.bytes[..size_limit];
        write!(f, "ContractWasm({:?})", base16::encode_lower(sliced_bytes))
    }
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

    use super::ContractWasm;

    pub fn contract_wasm_arb() -> impl Strategy<Value = ContractWasm> {
        vec(any::<u8>(), 1..1000).prop_map(ContractWasm::new)
    }
}
