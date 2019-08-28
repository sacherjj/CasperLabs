use contract_ffi::bytesrepr;
use contract_ffi::bytesrepr::{FromBytes, ToBytes};
use engine_wasm_prep::wasm_costs::{WasmCosts, WASM_COSTS_SIZE_SERIALIZED};

/// Represents the state of a protocol, intended to be associated with a given protocol version.
#[derive(Debug, PartialEq, Eq)]
pub struct ProtocolState {
    wasm_costs: WasmCosts,
}

impl ProtocolState {
    /// Creates a new [`ProtocolState`] value from a given [`WasmCosts`] value.
    pub fn new(wasm_costs: WasmCosts) -> Self {
        ProtocolState { wasm_costs }
    }

    /// Gets the [`WasmCosts`] value from a given [`ProtocolState`] value.
    pub fn wasm_costs(&self) -> &WasmCosts {
        &self.wasm_costs
    }
}

impl ToBytes for ProtocolState {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut ret: Vec<u8> = Vec::with_capacity(WASM_COSTS_SIZE_SERIALIZED);
        ret.append(&mut self.wasm_costs.to_bytes()?);
        Ok(ret)
    }
}

impl FromBytes for ProtocolState {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (wasm_costs, rem): (WasmCosts, &[u8]) = FromBytes::from_bytes(bytes)?;
        Ok((ProtocolState { wasm_costs }, rem))
    }
}

#[cfg(test)]
mod tests {
    use engine_shared::test_utils;
    use engine_wasm_prep::wasm_costs::WasmCosts;

    use super::ProtocolState;

    #[test]
    fn should_serialize_and_deserialize() {
        let v1 = {
            let costs = WasmCosts::from_version(1).unwrap();
            ProtocolState::new(costs)
        };
        let free = {
            let costs = WasmCosts::free();
            ProtocolState::new(costs)
        };
        assert!(test_utils::test_serialization_roundtrip(&v1));
        assert!(test_utils::test_serialization_roundtrip(&free));
    }
}
