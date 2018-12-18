extern crate parity_wasm;
extern crate pwasm_utils;

use parity_wasm::elements;
use elements::{deserialize_buffer, Error as ParityWasmError, Module};
use pwasm_utils::externalize_mem;
use std::error::Error;

const ALLOWED_IMPORTS: &'static [&'static str] = &[
    "read_value",
    "get_read",
    "write",
    "add",
    "new_uref",
    "serialize_function",
    "get_function",
    "load_arg",
    "get_arg",
    "ret",
    "call_contract",
    "get_call_result"
];

pub fn process(module_bytes: &[u8]) -> Result<Module, String> {
    // type annotation in closure needed
    let err_to_string = |err: ParityWasmError| err.description().to_owned();
    let module = deserialize_buffer(module_bytes).map_err(err_to_string)?;
    // 1. check that wasm module adheres to the specification of our 
    let mut ext_mod = externalize_mem(module, None, 128);
    remove_memory_export(&mut ext_mod)?;
	validate_imports(&ext_mod)?;
    Ok(ext_mod)
}

fn validate_imports(module: &Module) -> Result<(), String> {
    for section in module.sections() {
        let mut has_imported_memory_properly_named = false;
        match *section {
            elements::Section::Import(ref import_section) => {
                for entry in import_section.entries() {
                    if entry.module() != "env" {
                        return Err(String::from("All imports should be from env"))
                    } else {
                        match *entry.external() {
                            elements::External::Function(_) => {
                                if !ALLOWED_IMPORTS.contains(&entry.field()) {
                                    return Err(format!("'{}' is not supported by the runtime",  entry.field()))

                                }
                            },
                            elements::External::Memory(m) => {
                                if entry.field() == "memory" {
                                    has_imported_memory_properly_named  = true;

                                }

                                let max = if let Some(max)  = m.limits().maximum() {
                                    max
                                } else  {
                                    return Err(String::from("There is a limit to Wasm memory. This program does not limit memory"))
                                };

                                if max > 16 {
                                    return Err(format!("Wasm runtime has 1Mb limit (16 pages) on max contract memory. This program specific {}", max))
                                }
                            },
                            elements::External::Global(_) => {
                                return Err(String::from("No globals are provided with the runtime."))
                            },
                            _ => { continue; }
                        }
                    }
                    if !has_imported_memory_properly_named {
                        return Err(String::from("No imported memory from env::memory."))
                    }
                }
            }
            _ => { continue; }
        }
    }
    Ok(())
}

fn remove_memory_export(module: &mut Module) -> Result<(), String> {
    let exports = module
        .export_section_mut()
        .ok_or("Exports section must exist")?;
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
