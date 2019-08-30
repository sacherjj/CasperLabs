use contract_ffi::bytesrepr;
use contract_ffi::bytesrepr::{FromBytes, ToBytes};
use engine_wasm_prep::wasm_costs::{WasmCosts, WASM_COSTS_SIZE_SERIALIZED};

/// Represents a protocol's data. Intended to be associated with a given protocol version.
#[derive(Debug, PartialEq, Eq)]
pub struct ProtocolData {
    wasm_costs: WasmCosts,
}

impl ProtocolData {
    /// Creates a new [`ProtocolData`] value from a given [`WasmCosts`] value.
    pub fn new(wasm_costs: WasmCosts) -> Self {
        ProtocolData { wasm_costs }
    }

    /// Gets the [`WasmCosts`] value from a given [`ProtocolData`] value.
    pub fn wasm_costs(&self) -> &WasmCosts {
        &self.wasm_costs
    }
}

impl ToBytes for ProtocolData {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut ret: Vec<u8> = Vec::with_capacity(WASM_COSTS_SIZE_SERIALIZED);
        ret.append(&mut self.wasm_costs.to_bytes()?);
        Ok(ret)
    }
}

impl FromBytes for ProtocolData {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (wasm_costs, rem): (WasmCosts, &[u8]) = FromBytes::from_bytes(bytes)?;
        Ok((ProtocolData { wasm_costs }, rem))
    }
}

#[cfg(test)]
mod gens {
    use proptest::prop_compose;

    use engine_wasm_prep::wasm_costs::gens;

    use super::ProtocolData;

    prop_compose! {
        pub fn protocol_data_arb()(wasm_costs in gens::wasm_costs_arb()) -> ProtocolData {
            ProtocolData { wasm_costs }
        }
    }
}

#[cfg(test)]
mod tests {
    use proptest::proptest;

    use engine_shared::test_utils;
    use engine_wasm_prep::wasm_costs::WasmCosts;

    use super::{gens, ProtocolData};

    #[test]
    fn should_serialize_and_deserialize() {
        let v1 = {
            let costs = WasmCosts::from_version(1).unwrap();
            ProtocolData::new(costs)
        };
        let free = {
            let costs = WasmCosts::free();
            ProtocolData::new(costs)
        };
        assert!(test_utils::test_serialization_roundtrip(&v1));
        assert!(test_utils::test_serialization_roundtrip(&free));
    }

    proptest! {
        #[test]
        fn should_serialize_and_deserialize_with_arbitrary_values(
            protocol_data in gens::protocol_data_arb()
        ) {
            assert!(test_utils::test_serialization_roundtrip(&protocol_data));
        }
    }
}
