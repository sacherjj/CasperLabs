use contract_ffi::bytesrepr;
use contract_ffi::bytesrepr::{FromBytes, ToBytes};
use contract_ffi::uref::{URef, UREF_SIZE_SERIALIZED};
use engine_wasm_prep::wasm_costs::{WasmCosts, WASM_COSTS_SIZE_SERIALIZED};

const PROTOCOL_DATA_SIZE_SERIALIZED: usize =
    WASM_COSTS_SIZE_SERIALIZED + UREF_SIZE_SERIALIZED + UREF_SIZE_SERIALIZED;

/// Represents a protocol's data. Intended to be associated with a given protocol version.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProtocolData {
    wasm_costs: WasmCosts,
    mint: URef,
    proof_of_stake: URef,
}

impl ProtocolData {
    /// Creates a new [`ProtocolData`] value from a given [`WasmCosts`] value.
    pub fn new(wasm_costs: WasmCosts, mint: URef, proof_of_stake: URef) -> Self {
        ProtocolData {
            wasm_costs,
            mint,
            proof_of_stake,
        }
    }

    /// Gets the [`WasmCosts`] value from a given [`ProtocolData`] value.
    pub fn wasm_costs(&self) -> &WasmCosts {
        &self.wasm_costs
    }

    pub fn mint(&self) -> URef {
        self.mint
    }

    pub fn proof_of_stake(&self) -> URef {
        self.proof_of_stake
    }
}

impl ToBytes for ProtocolData {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut ret: Vec<u8> = Vec::with_capacity(PROTOCOL_DATA_SIZE_SERIALIZED);
        ret.append(&mut self.wasm_costs.to_bytes()?);
        ret.append(&mut self.mint.to_bytes()?);
        ret.append(&mut self.proof_of_stake.to_bytes()?);
        Ok(ret)
    }
}

impl FromBytes for ProtocolData {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (wasm_costs, rem): (WasmCosts, &[u8]) = FromBytes::from_bytes(bytes)?;
        let (mint_reference, rem): (URef, &[u8]) = FromBytes::from_bytes(rem)?;
        let (proof_of_stake_reference, rem): (URef, &[u8]) = FromBytes::from_bytes(rem)?;
        Ok((
            ProtocolData {
                wasm_costs,
                mint: mint_reference,
                proof_of_stake: proof_of_stake_reference,
            },
            rem,
        ))
    }
}

#[cfg(test)]
pub(crate) mod gens {
    use proptest::prop_compose;

    use contract_ffi::gens;
    use engine_wasm_prep::wasm_costs::gens as wasm_costs_gens;

    use super::ProtocolData;

    prop_compose! {
        pub fn protocol_data_arb()(
            wasm_costs in wasm_costs_gens::wasm_costs_arb(),
            mint in gens::uref_arb(),
            proof_of_stake in gens::uref_arb(),
        ) -> ProtocolData {
            ProtocolData {
                wasm_costs,
                mint,
                proof_of_stake,
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use proptest::proptest;

    use contract_ffi::uref::{AccessRights, URef};
    use contract_ffi::value::ProtocolVersion;
    use engine_shared::test_utils;
    use engine_wasm_prep::wasm_costs::WasmCosts;

    use super::{gens, ProtocolData};

    #[test]
    fn should_serialize_and_deserialize() {
        let v1 = {
            let costs = WasmCosts::from_version(ProtocolVersion::new(1)).unwrap();
            let mint_reference = URef::new([0u8; 32], AccessRights::READ_ADD_WRITE);
            let proof_of_stake_reference = URef::new([1u8; 32], AccessRights::READ_ADD_WRITE);
            ProtocolData::new(costs, mint_reference, proof_of_stake_reference)
        };
        let free = {
            let costs = WasmCosts::free();
            let mint_reference = URef::new([0u8; 32], AccessRights::READ_ADD_WRITE);
            let proof_of_stake_reference = URef::new([1u8; 32], AccessRights::READ_ADD_WRITE);
            ProtocolData::new(costs, mint_reference, proof_of_stake_reference)
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
