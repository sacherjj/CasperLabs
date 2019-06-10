use std::cell::RefCell;

use wasmi::memory_units::Pages;
use wasmi::{
    Error as InterpreterError, FuncRef, MemoryDescriptor, MemoryInstance, Signature, ValueType,
};
use wasmi::{FuncInstance, MemoryRef, ModuleImportResolver};

use super::error::ResolverError;
use super::memory_resolver::MemoryResolver;
use functions::*;

pub struct RuntimeModuleImportResolver {
    memory: RefCell<Option<MemoryRef>>,
    max_memory: u32,
}

impl Default for RuntimeModuleImportResolver {
    fn default() -> Self {
        RuntimeModuleImportResolver {
            memory: RefCell::new(None),
            max_memory: 64,
        }
    }
}

impl MemoryResolver for RuntimeModuleImportResolver {
    fn memory_ref(&self) -> Result<MemoryRef, ResolverError> {
        self.memory
            .borrow()
            .as_ref()
            .map(Clone::clone)
            .ok_or(ResolverError::NoImportedMemory)
    }
}

impl ModuleImportResolver for RuntimeModuleImportResolver {
    fn resolve_func(
        &self,
        field_name: &str,
        _signature: &Signature,
    ) -> Result<FuncRef, InterpreterError> {
        let func_ref = match field_name {
            "read_value" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 2][..], Some(ValueType::I32)),
                READ_FUNC_INDEX,
            ),
            "serialize_function" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 2][..], Some(ValueType::I32)),
                SER_FN_FUNC_INDEX,
            ),
            "write" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 4][..], None),
                WRITE_FUNC_INDEX,
            ),
            "get_read" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                GET_READ_FUNC_INDEX,
            ),
            "get_function" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                GET_FN_FUNC_INDEX,
            ),
            "add" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 4][..], None),
                ADD_FUNC_INDEX,
            ),
            "new_uref" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 3][..], None),
                NEW_FUNC_INDEX,
            ),
            "load_arg" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], Some(ValueType::I32)),
                LOAD_ARG_FUNC_INDEX,
            ),
            "get_arg" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                GET_ARG_FUNC_INDEX,
            ),
            "ret" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 4][..], None),
                RET_FUNC_INDEX,
            ),
            "call_contract" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 6][..], Some(ValueType::I32)),
                CALL_CONTRACT_FUNC_INDEX,
            ),
            "get_call_result" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                GET_CALL_RESULT_FUNC_INDEX,
            ),
            "get_uref" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 3][..], None),
                GET_UREF_FUNC_INDEX,
            ),
            "has_uref_name" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 2][..], Some(ValueType::I32)),
                HAS_UREF_FUNC_INDEX,
            ),
            "add_uref" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 4][..], None),
                ADD_UREF_FUNC_INDEX,
            ),
            "gas" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                GAS_FUNC_INDEX,
            ),
            "store_function" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 5][..], None),
                STORE_FN_INDEX,
            ),
            "protocol_version" => FuncInstance::alloc_host(
                Signature::new(vec![], Some(ValueType::I64)),
                PROTOCOL_VERSION_FUNC_INDEX,
            ),
            "is_valid" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 2][..], Some(ValueType::I32)),
                IS_VALID_FN_INDEX,
            ),
            "seed" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                SEED_FN_INDEX,
            ),
            "add_key" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 3][..], Some(ValueType::I32)),
                ADD_KEY_FN_INDEX,
            ),
            "remove_key" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 2][..], Some(ValueType::I32)),
                REMOVE_KEY_FN_INDEX,
            ),
            _ => {
                return Err(InterpreterError::Function(format!(
                    "host module doesn't export function with name {}",
                    field_name
                )));
            }
        };
        Ok(func_ref)
    }

    fn resolve_memory(
        &self,
        field_name: &str,
        descriptor: &MemoryDescriptor,
    ) -> Result<MemoryRef, InterpreterError> {
        if field_name == "memory" {
            let effective_max = descriptor.maximum().unwrap_or(self.max_memory + 1);
            if descriptor.initial() > self.max_memory || effective_max > self.max_memory {
                Err(InterpreterError::Instantiation(
                    "Module requested too much memory".to_owned(),
                ))
            } else {
                // Note: each "page" is 64 KiB
                let mem = MemoryInstance::alloc(
                    Pages(descriptor.initial() as usize),
                    descriptor.maximum().map(|x| Pages(x as usize)),
                )?;
                *self.memory.borrow_mut() = Some(mem.clone());
                Ok(mem)
            }
        } else {
            Err(InterpreterError::Instantiation(
                "Memory imported under unknown name".to_owned(),
            ))
        }
    }
}
