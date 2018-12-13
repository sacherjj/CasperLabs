extern crate parity_wasm;
extern crate pwasm_utils;

use parity_wasm::elements::{deserialize_buffer, Error as ParityWasmError, Module};
use pwasm_utils::externalize_mem;
use std::error::Error;

pub fn process(module_bytes: &[u8]) -> Result<Module, String> {
    // type annotation in closure needed
    let err_to_string = |err: ParityWasmError| err.description().to_owned();
    let module = deserialize_buffer(module_bytes).map_err(err_to_string)?;
    let mut ext_mod = externalize_mem(module, None, 128);
    remove_memory_export(&mut ext_mod)?;
    Ok(ext_mod)
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
        Err(String::from("Missing exported memory section."))
    }
}
