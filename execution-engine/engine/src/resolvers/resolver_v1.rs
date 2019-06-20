use std::cell::RefCell;

use wasmi::memory_units::Pages;
use wasmi::{
    Error as InterpreterError, FuncRef, MemoryDescriptor, MemoryInstance, Signature, ValueType,
};
use wasmi::{FuncInstance, MemoryRef, ModuleImportResolver};

use super::error::ResolverError;
use super::memory_resolver::MemoryResolver;
use function_index::FunctionIndex;

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
                FunctionIndex::ReadFuncIndex.into(),
            ),
            "read_value_local" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 2][..], Some(ValueType::I32)),
                FunctionIndex::ReadLocalFuncIndex.into(),
            ),
            "serialize_function" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 2][..], Some(ValueType::I32)),
                FunctionIndex::SerFnFuncIndex.into(),
            ),
            "write" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 4][..], None),
                FunctionIndex::WriteFuncIndex.into(),
            ),
            "write_local" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 4][..], None),
                FunctionIndex::WriteLocalFuncIndex.into(),
            ),
            "get_read" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                FunctionIndex::GetReadFuncIndex.into(),
            ),
            "get_function" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                FunctionIndex::GetFnFuncIndex.into(),
            ),
            "add" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 4][..], None),
                FunctionIndex::AddFuncIndex.into(),
            ),
            "new_uref" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 3][..], None),
                FunctionIndex::NewFuncIndex.into(),
            ),
            "load_arg" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], Some(ValueType::I32)),
                FunctionIndex::LoadArgFuncIndex.into(),
            ),
            "get_arg" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                FunctionIndex::GetArgFuncIndex.into(),
            ),
            "ret" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 4][..], None),
                FunctionIndex::RetFuncIndex.into(),
            ),
            "call_contract" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 6][..], Some(ValueType::I32)),
                FunctionIndex::CallContractFuncIndex.into(),
            ),
            "get_call_result" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                FunctionIndex::GetCallResultFuncIndex.into(),
            ),
            "get_uref" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 3][..], None),
                FunctionIndex::GetURefFuncIndex.into(),
            ),
            "has_uref_name" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 2][..], Some(ValueType::I32)),
                FunctionIndex::HasURefFuncIndex.into(),
            ),
            "add_uref" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 4][..], None),
                FunctionIndex::AddURefFuncIndex.into(),
            ),
            "gas" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                FunctionIndex::GasFuncIndex.into(),
            ),
            "store_function" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 5][..], None),
                FunctionIndex::StoreFnIndex.into(),
            ),
            "protocol_version" => FuncInstance::alloc_host(
                Signature::new(vec![], Some(ValueType::I64)),
                FunctionIndex::ProtocolVersionFuncIndex.into(),
            ),
            "is_valid" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 2][..], Some(ValueType::I32)),
                FunctionIndex::IsValidFnIndex.into(),
            ),
            "revert" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                FunctionIndex::RevertFuncIndex.into(),
            ),
            "add_associated_key" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 2][..], Some(ValueType::I32)),
                FunctionIndex::AddAssociatedKeyFuncIndex.into(),
            ),
            "remove_associated_key" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], Some(ValueType::I32)),
                FunctionIndex::RemoveAssociatedKeyFuncIndex.into(),
            ),
            "set_action_threshold" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 2][..], Some(ValueType::I32)),
                FunctionIndex::SetActionThresholdFuncIndex.into(),
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
