extern crate parity_wasm;
extern crate pwasm_utils;

extern crate contract_ffi;
#[cfg(test)]
extern crate engine_shared;

pub mod wasm_costs;

use std::error::Error;
use std::fmt::{self, Display, Formatter};

use parity_wasm::elements::{Error as ParityWasmError, Module};
use pwasm_utils::{externalize_mem, inject_gas_counter, rules};
use wasm_costs::WasmCosts;

//NOTE: size of Wasm memory page is 64 KiB
pub const MEM_PAGES: u32 = 64;

#[derive(Debug)]
pub enum PreprocessingError {
    InvalidImportsError(String),
    NoExportSection,
    NoImportSection,
    DeserializeError(String),
    OperationForbiddenByGasRules,
    StackLimiterError,
}

impl Display for PreprocessingError {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        match self {
            PreprocessingError::InvalidImportsError(error) => write!(f, "Invalid imports error: {}", error),
            PreprocessingError::NoExportSection => write!(f, "No export section found"),
            PreprocessingError::NoImportSection => write!(f, "No import section found"),
            PreprocessingError::DeserializeError(error) => write!(f, "Deserialization error: {}", error),
            PreprocessingError::OperationForbiddenByGasRules => write!(f, "Encountered operation forbidden by gas rules. Consult instruction -> metering config map"),
            PreprocessingError::StackLimiterError => write!(f, "Stack limiter error"),
        }
    }
}

use PreprocessingError::*;

pub struct Preprocessor {
    wasm_costs: WasmCosts,
    // Number of memory pages.
    mem_pages: u32,
}

impl Preprocessor {
    pub fn new(wasm_costs: WasmCosts) -> Self {
        Self {
            wasm_costs,
            mem_pages: MEM_PAGES,
        }
    }

    pub fn preprocess(&self, module_bytes: &[u8]) -> Result<Module, PreprocessingError> {
        let deserialized_module = self.deserialize(module_bytes)?;
        let ext_mod = externalize_mem(deserialized_module, None, self.mem_pages);
        let gas_mod = inject_gas_counters(ext_mod, &self.wasm_costs)?;
        let module =
            pwasm_utils::stack_height::inject_limiter(gas_mod, self.wasm_costs.max_stack_height)
                .map_err(|_| StackLimiterError)?;
        Ok(module)
    }

    // returns a parity Module from bytes without making modifications or limits
    pub fn deserialize(&self, module_bytes: &[u8]) -> Result<Module, PreprocessingError> {
        let from_parity_err = |err: ParityWasmError| DeserializeError(err.description().to_owned());
        let module =
            parity_wasm::deserialize_buffer::<Module>(&module_bytes).map_err(from_parity_err)?;
        Ok(module)
    }
}

fn gas_rules(wasm_costs: &WasmCosts) -> rules::Set {
    rules::Set::new(wasm_costs.regular, {
        let mut vals = ::std::collections::BTreeMap::new();
        vals.insert(
            rules::InstructionType::Load,
            rules::Metering::Fixed(wasm_costs.mem as u32),
        );
        vals.insert(
            rules::InstructionType::Store,
            rules::Metering::Fixed(wasm_costs.mem as u32),
        );
        vals.insert(
            rules::InstructionType::Div,
            rules::Metering::Fixed(wasm_costs.div as u32),
        );
        vals.insert(
            rules::InstructionType::Mul,
            rules::Metering::Fixed(wasm_costs.mul as u32),
        );
        vals
    })
    .with_grow_cost(wasm_costs.grow_mem)
    .with_forbidden_floats()
}

fn inject_gas_counters(
    module: Module,
    wasm_costs: &WasmCosts,
) -> Result<Module, PreprocessingError> {
    inject_gas_counter(module, &gas_rules(wasm_costs)).map_err(|_| OperationForbiddenByGasRules)
}
