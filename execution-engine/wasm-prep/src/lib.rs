extern crate parity_wasm;
extern crate pwasm_utils;
extern crate vm;

use parity_wasm::elements::{self, deserialize_buffer, Error as ParityWasmError, Module};
use pwasm_utils::{externalize_mem, inject_gas_counter, rules};
use std::error::Error;
use std::iter::Iterator;
use vm::wasm_costs::WasmCosts;

const ALLOWED_IMPORTS: &[&str] = &[
    "read_value",
    "get_read",
    "write",
    "add",
    "new_uref",
    "serialize_function",
    "store_function",
    "get_function",
    "load_arg",
    "get_arg",
    "ret",
    "call_contract",
    "get_call_result",
    "get_uref",
    "has_uref_name",
    "add_uref",
];

const MEM_PAGES: u32 = 128;
pub const MAX_MEM_PAGES: u32 = 305; // 10mb

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

// TODO(mateusz.gorski): Add `protocol_version` field.
pub struct WasmiPreprocessor {
    wasm_costs: WasmCosts,
    // Number of memory pages.
    mem_pages: u32,
    max_mem_pages: u32,
}

impl WasmiPreprocessor {
    // TODO(mateusz.gorski): Add from_protocol_version method,
    // for creating WasmiPreprocessor based on it.
    pub fn new(wasm_costs: WasmCosts, mem_pages: u32, max_mem_pages: u32) -> WasmiPreprocessor {
        WasmiPreprocessor {
            wasm_costs,
            mem_pages,
            max_mem_pages,
        }
    }
}

impl Default for WasmiPreprocessor {
    fn default() -> WasmiPreprocessor {
        WasmiPreprocessor::new(Default::default(), MEM_PAGES, MAX_MEM_PAGES)
    }
}

impl Preprocessor<Module> for WasmiPreprocessor {
    fn preprocess(&self, module_bytes: &[u8]) -> Result<Module, PreprocessingError> {
        let from_parity_err = |err: ParityWasmError| DeserializeError(err.description().to_owned());
        let deserialized_module = deserialize_buffer(module_bytes).map_err(from_parity_err)?;
        let mut ext_mod = externalize_mem(deserialized_module, None, self.mem_pages);
        remove_memory_export(&mut ext_mod)?;
        validate_imports(&ext_mod, self.max_mem_pages)?;
        let gas_mod = inject_gas_counters(ext_mod, &self.wasm_costs)?;
        let module =
            pwasm_utils::stack_height::inject_limiter(gas_mod, self.wasm_costs.max_stack_height)
                .map_err(|_| StackLimiterError)?;
        // TODO(mateusz.gorski): Inject global constant that specifies PROTOCOL_VERSION
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

fn invalid_imports<E: AsRef<str>>(s: E) -> PreprocessingError {
    InvalidImportsError(s.as_ref().to_string())
}

fn invalid_imports_error<T, E: AsRef<str>>(s: E) -> Result<T, PreprocessingError> {
    Err(invalid_imports(s))
}

fn validate_imports(module: &Module, max_mem_pages: u32) -> Result<(), PreprocessingError> {
    module
        .import_section()
        .ok_or(NoImportSection)?
        .entries()
        .iter()
        .try_fold(false, |has_imported_memory_properly_named, ref entry| {
            if entry.module() != "env" {
                return invalid_imports_error("All imports should be from env");
            }
            match *entry.external() {
                elements::External::Function(_) => {
                    if !ALLOWED_IMPORTS.contains(&entry.field()) {
                        invalid_imports_error::<bool, String>(format!(
                            "'{}' is not supported by the runtime",
                            entry.field()
                        ))
                    } else {
                        Ok(has_imported_memory_properly_named)
                    }
                }
                elements::External::Memory(m) => {
                    let max = m.limits().maximum().ok_or_else(|| {
                        invalid_imports(
                            "There is a limit to Wasm memory. This program does not limit memory",
                        )
                    })?;
                    if max > max_mem_pages {
                        return invalid_imports_error::<bool, String>(format!(
                            "Wasm runtime has 10Mb limit (305 pages each 64KiB) on \
                             max contract memory. This program specific {}",
                            max
                        ));
                    }
                    Ok(entry.field() == "memory") // memory properly imported
                }
                elements::External::Global(_) => {
                    invalid_imports_error::<bool, &str>("No globals are provided with the runtime.")
                }
                elements::External::Table(_) => Ok(has_imported_memory_properly_named),
            }
        })
        .and_then(|had_imported_memory_properly_named| {
            if had_imported_memory_properly_named {
                Ok(())
            } else {
                invalid_imports_error::<(), &str>("No imported memory from env::memory.")
            }
        })
}

fn remove_memory_export(module: &mut Module) -> Result<(), PreprocessingError> {
    let exports = module.export_section_mut().ok_or(NoExportSection)?;
    let entries = exports.entries_mut();
    let mem_pos = entries.iter().position(|e| e.field() == "memory");
    if let Some(mem_pos) = mem_pos {
        entries.remove(mem_pos);
        Ok(())
    } else {
        // noop: it is OK if module doesn't export memory section.
        Ok(())
    }
}
