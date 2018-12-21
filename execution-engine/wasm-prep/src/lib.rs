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

const MEM_PAGES: u32 = 128;
const MAX_MEM_PAGES: u32 = 305; // 10mb

#[derive(Debug)]
pub enum PreprocessingError {
    InvalidImportsError(String),
    NoExportSection,
    NoImportSection,
    DeserializeError(String)
}

use PreprocessingError::*;

pub fn process(module_bytes: &[u8]) -> Result<Module, PreprocessingError> {
    // type annotation in closure needed
    let err_to_string = |err: ParityWasmError| err.description().to_owned();
    let module = deserialize_buffer(module_bytes).map_err(err_to_string).map_err(|error| DeserializeError(error))?;
    let mut ext_mod = externalize_mem(module, None, MEM_PAGES);
    remove_memory_export(&mut ext_mod)?;
	validate_imports(&ext_mod)?;
    Ok(ext_mod)
}

fn invalid_imports<E: AsRef<str>>(s: E) -> PreprocessingError {
    InvalidImportsError(s.as_ref().to_string())
}

fn invalid_imports_error<T, E: AsRef<str>>(s: E) -> Result<T, PreprocessingError> {
    Err(invalid_imports(s))
}

fn validate_imports(module: &Module) -> Result<(), PreprocessingError> {
    module
        .import_section()
        .ok_or(NoImportSection)
        .and_then(|import_section| {
            import_section
                .entries()
                .iter()
                .try_fold(false, |has_imported_memory_properly_named, ref entry| {
                    if entry.module() != "env" {
                        invalid_imports_error("All imports should be from env")
                    } else {
                        match *entry.external() {
                            elements::External::Function(_) => {
                                if !ALLOWED_IMPORTS.contains(&entry.field()) {
                                    invalid_imports_error::<bool, String>(format!("'{}' is not supported by the runtime",  entry.field()))
                                } else {
                                    Ok(has_imported_memory_properly_named)
                                }
                            },
                            elements::External::Memory(m) => {
                                m
                                    .limits()
                                    .maximum()
                                    .ok_or(invalid_imports("There is a limit to Wasm memory. This program does not limit memory"))
                                    .and_then(|max| {
                                        if max > MAX_MEM_PAGES {
                                            invalid_imports_error::<bool, String>(format!("Wasm runtime has 10Mb limit (305 pages each 64KiB) on max contract memory. This program specific {}", max))
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
                                invalid_imports_error::<bool, &str>("No globals are provided with the runtime.")
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
            invalid_imports_error::<(), &str>("No imported memory from env::memory.")
        }
    })
}

fn remove_memory_export(module: &mut Module) -> Result<(), PreprocessingError> {
    let exports = module
        .export_section_mut()
        .ok_or(NoExportSection)?;
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
