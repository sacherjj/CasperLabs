use engine_wasm_prep::wasm_costs::{WasmCosts, WASM_COSTS_SERIALIZED_LENGTH};
use types::{
    bytesrepr::{self, FromBytes, ToBytes},
    AccessRights, URef, UREF_SERIALIZED_LENGTH,
};

const PROTOCOL_DATA_SERIALIZED_LENGTH: usize =
    WASM_COSTS_SERIALIZED_LENGTH + UREF_SERIALIZED_LENGTH + UREF_SERIALIZED_LENGTH;

/// Represents a protocol's data. Intended to be associated with a given protocol version.
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub struct ProtocolData {
    wasm_costs: WasmCosts,
    mint: URef,
    proof_of_stake: URef,
}

/// Provides a default instance with non existing urefs and empty costs table.
///
/// Used in contexts where PoS or Mint contract is not ready yet, and pos, and
/// mint installers are ran. For use with caution.
impl Default for ProtocolData {
    fn default() -> ProtocolData {
        ProtocolData {
            wasm_costs: WasmCosts::default(),
            mint: URef::new([0; 32], AccessRights::READ),
            proof_of_stake: URef::new([0; 32], AccessRights::READ),
        }
    }
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

    /// Retrieves all valid system contracts stored in protocol version
    pub fn system_contracts(&self) -> Vec<URef> {
        let mut vec = Vec::with_capacity(2);
        if self.mint.addr() != [0; 32] {
            vec.push(self.mint)
        }
        if self.proof_of_stake.addr() != [0; 32] {
            vec.push(self.proof_of_stake)
        }
        vec
    }
}

impl ToBytes for ProtocolData {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut ret: Vec<u8> = Vec::with_capacity(PROTOCOL_DATA_SERIALIZED_LENGTH);
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

    use engine_wasm_prep::wasm_costs::gens as wasm_costs_gens;
    use types::gens;

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

    use engine_shared::test_utils;
    use types::{bytesrepr, AccessRights, URef};

    use super::{gens, ProtocolData};

    #[test]
    fn should_serialize_and_deserialize() {
        let mock = {
            let costs = test_utils::wasm_costs_mock();
            let mint_reference = URef::new([0u8; 32], AccessRights::READ_ADD_WRITE);
            let proof_of_stake_reference = URef::new([1u8; 32], AccessRights::READ_ADD_WRITE);
            ProtocolData::new(costs, mint_reference, proof_of_stake_reference)
        };
        let free = {
            let costs = test_utils::wasm_costs_free();
            let mint_reference = URef::new([0u8; 32], AccessRights::READ_ADD_WRITE);
            let proof_of_stake_reference = URef::new([1u8; 32], AccessRights::READ_ADD_WRITE);
            ProtocolData::new(costs, mint_reference, proof_of_stake_reference)
        };
        bytesrepr::test_serialization_roundtrip(&mock);
        bytesrepr::test_serialization_roundtrip(&free);
    }

    #[test]
    fn should_return_all_system_contracts() {
        let mint_reference = URef::new([197u8; 32], AccessRights::READ_ADD_WRITE);
        let proof_of_stake_reference = URef::new([198u8; 32], AccessRights::READ_ADD_WRITE);
        let protocol_data = {
            let costs = test_utils::wasm_costs_mock();
            ProtocolData::new(costs, mint_reference, proof_of_stake_reference)
        };

        let actual = {
            let mut items = protocol_data.system_contracts();
            items.sort();
            items
        };

        assert_eq!(actual.len(), 2);
        assert_eq!(actual[0], mint_reference);
        assert_eq!(actual[1], proof_of_stake_reference);
    }

    #[test]
    fn should_return_only_valid_system_contracts() {
        assert_eq!(ProtocolData::default().system_contracts(), &[]);

        let mint_reference = URef::new([199u8; 32], AccessRights::READ_ADD_WRITE);
        let proof_of_stake_reference = URef::new([0u8; 32], AccessRights::READ);
        let protocol_data = {
            let costs = test_utils::wasm_costs_mock();
            ProtocolData::new(costs, mint_reference, proof_of_stake_reference)
        };

        let actual = {
            let mut items = protocol_data.system_contracts();
            items.sort();
            items
        };

        assert_eq!(actual.len(), 1);
        assert_eq!(actual[0], mint_reference);
    }

    proptest! {
        #[test]
        fn should_serialize_and_deserialize_with_arbitrary_values(
            protocol_data in gens::protocol_data_arb()
        ) {
            bytesrepr::test_serialization_roundtrip(&protocol_data);
        }
    }
}
