extern crate parity_wasm;
extern crate pwasm_utils;

use parity_wasm::elements::{self, deserialize_buffer, Error as ParityWasmError, Module};
use pwasm_utils::externalize_mem;
use std::error::Error;
use std::iter::Iterator;

const ALLOWED_IMPORTS: &'static [&'static str] = &[
    "read_value",
    "get_read",
    "write",
    "add",
    "new_uref",
    "serialize_function",
    "function_address",
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
    let mut ext_mod = externalize_mem(module, None, 128);
    remove_memory_export(&mut ext_mod)?;
	validate_imports(&ext_mod)?;
    Ok(ext_mod)
}

fn validate_imports(module: &Module) -> Result<(), String> {
    module
        .import_section()
        .ok_or("No imports section".to_owned())
        .and_then(|import_section| {
            import_section
                .entries()
                .iter()
                .try_fold(false, |has_imported_memory_properly_named, ref entry| {
                    if entry.module() != "env" {
                        Err(String::from("All imports should be from env"))
                    } else {
                        match *entry.external() {
                            elements::External::Function(_) => {
                                if !ALLOWED_IMPORTS.contains(&entry.field()) {
                                    Err(format!("'{}' is not supported by the runtime",  entry.field()))
                                } else {
                                    Ok(has_imported_memory_properly_named)
                                }
                            },
                            elements::External::Memory(m) => {
                                m
                                    .limits()
                                    .maximum()
                                    .ok_or(String::from("There is a limit to Wasm memory. This program does not limit memory"))
                                    .and_then(|max| {
                                        if max > 305 {
                                            Err(format!("Wasm runtime has 10Mb limit (305 pages each 64KiB) on max contract memory. This program specific {}", max))
                                        } else {
                                            Ok(has_imported_memory_properly_named)
                                        }
                                    })
                                    .and_then(|_| {
                                        if entry.field() == "memory" {
                                            Ok(true) // memory properly imported
                                        } else {
                                            Ok(false)
                                        }
                                    })
                            },
                            elements::External::Global(_) => {
                                Err(String::from("No globals are provided with the runtime."))
                            },
                            elements::External::Table(_) => { Ok(has_imported_memory_properly_named) }
                        }
                    }
                })
        })
    .and_then(|had_imported_memory_properly_named| {
        if had_imported_memory_properly_named {
            Ok(())
        } else {
            Err(String::from("No imported memory from env::memory."))
        }
    })
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
