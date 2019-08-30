use contract_ffi::bytesrepr;
use contract_ffi::bytesrepr::{FromBytes, ToBytes, U32_SIZE};

const NUM_FIELDS: usize = 10;
pub const WASM_COSTS_SIZE_SERIALIZED: usize = NUM_FIELDS * U32_SIZE;

// Taken (partially) from parity-ethereum
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub struct WasmCosts {
    /// Default opcode cost
    pub regular: u32,
    /// Div operations multiplier.
    pub div: u32,
    /// Mul operations multiplier.
    pub mul: u32,
    /// Memory (load/store) operations multiplier.
    pub mem: u32,
    /// Memory stipend. Amount of free memory (in 64kb pages) each contract can
    /// use for stack.
    pub initial_mem: u32,
    /// Grow memory cost, per page (64kb)
    pub grow_mem: u32,
    /// Memory copy cost, per byte
    pub memcpy: u32,
    /// Max stack height (native WebAssembly stack limiter)
    pub max_stack_height: u32,
    /// Cost of wasm opcode is calculated as TABLE_ENTRY_COST * `opcodes_mul` /
    /// `opcodes_div`
    pub opcodes_mul: u32,
    /// Cost of wasm opcode is calculated as TABLE_ENTRY_COST * `opcodes_mul` /
    /// `opcodes_div`
    pub opcodes_div: u32,
}

impl WasmCosts {
    pub fn from_version(protocol_version: u64) -> Option<WasmCosts> {
        match protocol_version {
            1 => Some(WasmCosts {
                regular: 1,
                div: 16,
                mul: 4,
                mem: 2,
                initial_mem: 4096,
                grow_mem: 8192,
                memcpy: 1,
                max_stack_height: 64 * 1024,
                opcodes_mul: 3,
                opcodes_div: 8,
            }),
            _ => None,
        }
    }

    pub fn free() -> WasmCosts {
        WasmCosts {
            regular: 0,
            div: 0,
            mul: 0,
            mem: 0,
            initial_mem: 4096,
            grow_mem: 8192,
            memcpy: 0,
            max_stack_height: 64 * 1024,
            opcodes_mul: 1,
            opcodes_div: 1,
        }
    }
}

impl ToBytes for WasmCosts {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut ret: Vec<u8> = Vec::with_capacity(WASM_COSTS_SIZE_SERIALIZED);
        ret.append(&mut self.regular.to_bytes()?);
        ret.append(&mut self.div.to_bytes()?);
        ret.append(&mut self.mul.to_bytes()?);
        ret.append(&mut self.mem.to_bytes()?);
        ret.append(&mut self.initial_mem.to_bytes()?);
        ret.append(&mut self.grow_mem.to_bytes()?);
        ret.append(&mut self.memcpy.to_bytes()?);
        ret.append(&mut self.max_stack_height.to_bytes()?);
        ret.append(&mut self.opcodes_mul.to_bytes()?);
        ret.append(&mut self.opcodes_div.to_bytes()?);
        Ok(ret)
    }
}

impl FromBytes for WasmCosts {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (regular, rem): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let (div, rem): (u32, &[u8]) = FromBytes::from_bytes(rem)?;
        let (mul, rem): (u32, &[u8]) = FromBytes::from_bytes(rem)?;
        let (mem, rem): (u32, &[u8]) = FromBytes::from_bytes(rem)?;
        let (initial_mem, rem): (u32, &[u8]) = FromBytes::from_bytes(rem)?;
        let (grow_mem, rem): (u32, &[u8]) = FromBytes::from_bytes(rem)?;
        let (memcpy, rem): (u32, &[u8]) = FromBytes::from_bytes(rem)?;
        let (max_stack_height, rem): (u32, &[u8]) = FromBytes::from_bytes(rem)?;
        let (opcodes_mul, rem): (u32, &[u8]) = FromBytes::from_bytes(rem)?;
        let (opcodes_div, rem): (u32, &[u8]) = FromBytes::from_bytes(rem)?;
        let wasm_costs = WasmCosts {
            regular,
            div,
            mul,
            mem,
            initial_mem,
            grow_mem,
            memcpy,
            max_stack_height,
            opcodes_mul,
            opcodes_div,
        };
        Ok((wasm_costs, rem))
    }
}

pub mod gens {
    use proptest::num;
    use proptest::prop_compose;

    use crate::wasm_costs::WasmCosts;

    prop_compose! {
        pub fn wasm_costs_arb()(
            regular in num::u32::ANY,
            div in num::u32::ANY,
            mul in num::u32::ANY,
            mem in num::u32::ANY,
            initial_mem in num::u32::ANY,
            grow_mem in num::u32::ANY,
            memcpy in num::u32::ANY,
            max_stack_height in num::u32::ANY,
            opcodes_mul in num::u32::ANY,
            opcodes_div in num::u32::ANY,
        ) -> WasmCosts {
            WasmCosts {
                regular,
                div,
                mul,
                mem,
                initial_mem,
                grow_mem,
                memcpy,
                max_stack_height,
                opcodes_mul,
                opcodes_div,
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use proptest::proptest;

    use engine_shared::test_utils;

    use super::{gens, WasmCosts};

    #[test]
    fn should_serialize_and_deserialize() {
        let v1 = WasmCosts::from_version(1).unwrap();
        let free = WasmCosts::free();
        assert!(test_utils::test_serialization_roundtrip(&v1));
        assert!(test_utils::test_serialization_roundtrip(&free));
    }

    proptest! {
        #[test]
        fn should_serialize_and_deserialize_with_arbitrary_values(
            wasm_costs in gens::wasm_costs_arb()
        ) {
            assert!(test_utils::test_serialization_roundtrip(&wasm_costs));
        }
    }
}
