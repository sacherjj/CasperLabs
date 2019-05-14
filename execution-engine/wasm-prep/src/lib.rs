extern crate parity_wasm;
extern crate pwasm_utils;
extern crate vm;

use parity_wasm::elements::{deserialize_buffer, Error as ParityWasmError, Module};
use pwasm_utils::{externalize_mem, inject_gas_counter, rules};
use std::error::Error;
use vm::wasm_costs::WasmCosts;

const MEM_PAGES: u32 = 128;

#[derive(Debug)]
pub enum PreprocessingError {
    InvalidImportsError(String),
    NoExportSection,
    NoImportSection,
    DeserializeError(String),
    OperationForbiddenByGasRules,
    StackLimiterError,
}

use PreprocessingError::*;

pub trait Preprocessor<A> {
    fn preprocess(&self, module_bytes: &[u8]) -> Result<A, PreprocessingError>;
}

pub struct WasmiPreprocessor {
    wasm_costs: WasmCosts,
    // Number of memory pages.
    mem_pages: u32,
}

impl WasmiPreprocessor {
    // TODO(mateusz.gorski): Add from_protocol_version method,
    // for creating WasmiPreprocessor based on it.
    pub fn new(wasm_costs: WasmCosts, mem_pages: u32) -> WasmiPreprocessor {
        WasmiPreprocessor {
            wasm_costs,
            mem_pages,
        }
    }
}

impl Default for WasmiPreprocessor {
    fn default() -> WasmiPreprocessor {
        WasmiPreprocessor::new(Default::default(), MEM_PAGES)
    }
}

impl Preprocessor<Module> for WasmiPreprocessor {
    fn preprocess(&self, module_bytes: &[u8]) -> Result<Module, PreprocessingError> {
        let from_parity_err = |err: ParityWasmError| DeserializeError(err.description().to_owned());
        let deserialized_module = deserialize_buffer(module_bytes).map_err(from_parity_err)?;
        let ext_mod = externalize_mem(deserialized_module, None, self.mem_pages);
        let gas_mod = inject_gas_counters(ext_mod, &self.wasm_costs)?;
        let module =
            pwasm_utils::stack_height::inject_limiter(gas_mod, self.wasm_costs.max_stack_height)
                .map_err(|_| StackLimiterError)?;
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
