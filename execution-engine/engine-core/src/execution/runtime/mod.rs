mod args;
mod externals;

use std::{
    cmp,
    collections::{BTreeMap, HashMap, HashSet},
    convert::TryFrom,
    iter::IntoIterator,
};

use itertools::Itertools;
use parity_wasm::elements::Module;
use wasmi::{ImportsBuilder, MemoryRef, ModuleInstance, ModuleRef, Trap, TrapKind};

use contract::args_parser::ArgsParser;
use engine_shared::{account::Account, contract::Contract, gas::Gas, stored_value::StoredValue};
use engine_storage::global_state::StateReader;
use types::{
    account::{ActionType, PublicKey, PurseId, Weight, PUBLIC_KEY_SERIALIZED_LENGTH},
    bytesrepr::{self, ToBytes},
    system_contract_errors,
    system_contract_errors::mint,
    AccessRights, ApiError, CLType, CLValue, Key, ProtocolVersion, SystemContractType,
    TransferResult, TransferredTo, URef, U128, U256, U512,
};

use super::{Error, MINT_NAME, POS_NAME};
use crate::{
    engine_state::system_contract_cache::SystemContractCache,
    resolvers::{create_module_resolver, memory_resolver::MemoryResolver},
    runtime_context::RuntimeContext,
    Address,
};

pub struct Runtime<'a, R> {
    system_contract_cache: SystemContractCache,
    memory: MemoryRef,
    module: Module,
    host_buf: Option<CLValue>,
    context: RuntimeContext<'a, R>,
}

/// Rename function called `name` in the `module` to `call`.
/// wasmi's entrypoint for a contracts is a function called `call`,
/// so we have to rename function before storing it in the GlobalState.
pub fn rename_export_to_call(module: &mut Module, name: String) {
    let main_export = module
        .export_section_mut()
        .unwrap()
        .entries_mut()
        .iter_mut()
        .find(|e| e.field() == name)
        .unwrap()
        .field_mut();
    main_export.clear();
    main_export.push_str("call");
}

pub fn instance_and_memory(
    parity_module: Module,
    protocol_version: ProtocolVersion,
) -> Result<(ModuleRef, MemoryRef), Error> {
    let module = wasmi::Module::from_parity_wasm_module(parity_module)?;
    let resolver = create_module_resolver(protocol_version)?;
    let mut imports = ImportsBuilder::new();
    imports.push_resolver("env", &resolver);
    let instance = ModuleInstance::new(&module, &imports)?.assert_no_start();

    let memory = resolver.memory_ref()?;
    Ok((instance, memory))
}

/// Turns `key` into a `([u8; 32], AccessRights)` tuple.
/// Returns None if `key` is not `Key::URef` as it wouldn't have `AccessRights`
/// associated with it. Helper function for creating `named_keys` associating
/// addresses and corresponding `AccessRights`.
pub fn key_to_tuple(key: Key) -> Option<([u8; 32], Option<AccessRights>)> {
    match key {
        Key::URef(uref) => Some((uref.addr(), uref.access_rights())),
        Key::Account(_) => None,
        Key::Hash(_) => None,
        Key::Local { .. } => None,
    }
}

/// Groups a collection of urefs by their addresses and accumulates access
/// rights per key
pub fn extract_access_rights_from_urefs<I: IntoIterator<Item = URef>>(
    input: I,
) -> HashMap<Address, HashSet<AccessRights>> {
    input
        .into_iter()
        .map(|uref: URef| (uref.addr(), uref.access_rights()))
        .group_by(|(key, _)| *key)
        .into_iter()
        .map(|(key, group)| {
            (
                key,
                group
                    .filter_map(|(_, x)| x)
                    .collect::<HashSet<AccessRights>>(),
            )
        })
        .collect()
}

/// Groups a collection of keys by their address and accumulates access rights
/// per key.
pub fn extract_access_rights_from_keys<I: IntoIterator<Item = Key>>(
    input: I,
) -> HashMap<Address, HashSet<AccessRights>> {
    input
        .into_iter()
        .map(key_to_tuple)
        .flatten()
        .group_by(|(key, _)| *key)
        .into_iter()
        .map(|(key, group)| {
            (
                key,
                group
                    .filter_map(|(_, x)| x)
                    .collect::<HashSet<AccessRights>>(),
            )
        })
        .collect()
}

#[allow(clippy::cognitive_complexity)]
fn extract_urefs(cl_value: &CLValue) -> Result<Vec<URef>, Error> {
    match cl_value.cl_type() {
        CLType::Bool
        | CLType::I32
        | CLType::I64
        | CLType::U8
        | CLType::U32
        | CLType::U64
        | CLType::U128
        | CLType::U256
        | CLType::U512
        | CLType::Unit
        | CLType::String
        | CLType::Any => Ok(vec![]),
        CLType::Option(ty) => match **ty {
            CLType::URef => {
                let opt: Option<URef> = cl_value.to_owned().into_t()?;
                Ok(opt.into_iter().collect())
            }
            CLType::Key => {
                let opt: Option<Key> = cl_value.to_owned().into_t()?;
                Ok(opt.into_iter().flat_map(Key::into_uref).collect())
            }
            _ => Ok(vec![]),
        },
        CLType::List(ty) => match **ty {
            CLType::URef => Ok(cl_value.to_owned().into_t()?),
            CLType::Key => {
                let keys: Vec<Key> = cl_value.to_owned().into_t()?;
                Ok(keys.into_iter().filter_map(Key::into_uref).collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 1) => match **ty {
            CLType::URef => {
                let arr: [URef; 1] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 1] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 2) => match **ty {
            CLType::URef => {
                let arr: [URef; 2] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 2] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 3) => match **ty {
            CLType::URef => {
                let arr: [URef; 3] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 3] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 4) => match **ty {
            CLType::URef => {
                let arr: [URef; 4] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 4] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 5) => match **ty {
            CLType::URef => {
                let arr: [URef; 5] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 5] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 6) => match **ty {
            CLType::URef => {
                let arr: [URef; 6] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 6] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 7) => match **ty {
            CLType::URef => {
                let arr: [URef; 7] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 7] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 8) => match **ty {
            CLType::URef => {
                let arr: [URef; 8] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 8] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 9) => match **ty {
            CLType::URef => {
                let arr: [URef; 9] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 9] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 10) => match **ty {
            CLType::URef => {
                let arr: [URef; 10] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 10] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 11) => match **ty {
            CLType::URef => {
                let arr: [URef; 11] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 11] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 12) => match **ty {
            CLType::URef => {
                let arr: [URef; 12] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 12] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 13) => match **ty {
            CLType::URef => {
                let arr: [URef; 13] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 13] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 14) => match **ty {
            CLType::URef => {
                let arr: [URef; 14] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 14] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 15) => match **ty {
            CLType::URef => {
                let arr: [URef; 15] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 15] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 16) => match **ty {
            CLType::URef => {
                let arr: [URef; 16] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 16] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 17) => match **ty {
            CLType::URef => {
                let arr: [URef; 17] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 17] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 18) => match **ty {
            CLType::URef => {
                let arr: [URef; 18] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 18] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 19) => match **ty {
            CLType::URef => {
                let arr: [URef; 19] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 19] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 20) => match **ty {
            CLType::URef => {
                let arr: [URef; 20] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 20] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 21) => match **ty {
            CLType::URef => {
                let arr: [URef; 21] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 21] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 22) => match **ty {
            CLType::URef => {
                let arr: [URef; 22] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 22] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 23) => match **ty {
            CLType::URef => {
                let arr: [URef; 23] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 23] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 24) => match **ty {
            CLType::URef => {
                let arr: [URef; 24] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 24] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 25) => match **ty {
            CLType::URef => {
                let arr: [URef; 25] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 25] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 26) => match **ty {
            CLType::URef => {
                let arr: [URef; 26] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 26] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 27) => match **ty {
            CLType::URef => {
                let arr: [URef; 27] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 27] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 28) => match **ty {
            CLType::URef => {
                let arr: [URef; 28] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 28] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 29) => match **ty {
            CLType::URef => {
                let arr: [URef; 29] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 29] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 30) => match **ty {
            CLType::URef => {
                let arr: [URef; 30] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 30] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 31) => match **ty {
            CLType::URef => {
                let arr: [URef; 31] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 31] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 32) => match **ty {
            CLType::URef => {
                let arr: [URef; 32] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 32] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 64) => match **ty {
            CLType::URef => {
                let arr: [URef; 64] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 64] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 128) => match **ty {
            CLType::URef => {
                let arr: [URef; 128] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 128] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 256) => match **ty {
            CLType::URef => {
                let arr: [URef; 256] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 256] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(ty, 512) => match **ty {
            CLType::URef => {
                let arr: [URef; 512] = cl_value.to_owned().into_t()?;
                Ok(arr.to_vec())
            }
            CLType::Key => {
                let arr: [Key; 512] = cl_value.to_owned().into_t()?;
                Ok(arr.iter().filter_map(Key::as_uref).cloned().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::FixedList(_ty, _) => Ok(vec![]),
        CLType::Result { ok, err } => match (&**ok, &**err) {
            (CLType::URef, CLType::Bool) => {
                let res: Result<URef, bool> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(uref) => Ok(vec![uref]),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::URef, CLType::I32) => {
                let res: Result<URef, i32> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(uref) => Ok(vec![uref]),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::URef, CLType::I64) => {
                let res: Result<URef, i64> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(uref) => Ok(vec![uref]),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::URef, CLType::U8) => {
                let res: Result<URef, u8> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(uref) => Ok(vec![uref]),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::URef, CLType::U32) => {
                let res: Result<URef, u32> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(uref) => Ok(vec![uref]),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::URef, CLType::U64) => {
                let res: Result<URef, u64> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(uref) => Ok(vec![uref]),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::URef, CLType::U128) => {
                let res: Result<URef, U128> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(uref) => Ok(vec![uref]),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::URef, CLType::U256) => {
                let res: Result<URef, U256> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(uref) => Ok(vec![uref]),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::URef, CLType::U512) => {
                let res: Result<URef, U512> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(uref) => Ok(vec![uref]),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::URef, CLType::Unit) => {
                let res: Result<URef, ()> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(uref) => Ok(vec![uref]),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::URef, CLType::String) => {
                let res: Result<URef, String> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(uref) => Ok(vec![uref]),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::URef, CLType::Key) => {
                let res: Result<URef, Key> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(uref) => Ok(vec![uref]),
                    Err(key) => Ok(key.into_uref().into_iter().collect()),
                }
            }
            (CLType::URef, CLType::URef) => {
                let res: Result<URef, URef> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(uref) => Ok(vec![uref]),
                    Err(uref) => Ok(vec![uref]),
                }
            }
            (CLType::Key, CLType::Bool) => {
                let res: Result<Key, bool> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(key) => Ok(key.into_uref().into_iter().collect()),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::Key, CLType::I32) => {
                let res: Result<Key, i32> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(key) => Ok(key.into_uref().into_iter().collect()),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::Key, CLType::I64) => {
                let res: Result<Key, i64> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(key) => Ok(key.into_uref().into_iter().collect()),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::Key, CLType::U8) => {
                let res: Result<Key, u8> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(key) => Ok(key.into_uref().into_iter().collect()),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::Key, CLType::U32) => {
                let res: Result<Key, u32> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(key) => Ok(key.into_uref().into_iter().collect()),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::Key, CLType::U64) => {
                let res: Result<Key, u64> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(key) => Ok(key.into_uref().into_iter().collect()),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::Key, CLType::U128) => {
                let res: Result<Key, U128> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(key) => Ok(key.into_uref().into_iter().collect()),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::Key, CLType::U256) => {
                let res: Result<Key, U256> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(key) => Ok(key.into_uref().into_iter().collect()),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::Key, CLType::U512) => {
                let res: Result<Key, U512> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(key) => Ok(key.into_uref().into_iter().collect()),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::Key, CLType::Unit) => {
                let res: Result<Key, ()> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(key) => Ok(key.into_uref().into_iter().collect()),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::Key, CLType::String) => {
                let res: Result<Key, String> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(key) => Ok(key.into_uref().into_iter().collect()),
                    Err(_) => Ok(vec![]),
                }
            }
            (CLType::Key, CLType::URef) => {
                let res: Result<Key, URef> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(key) => Ok(key.into_uref().into_iter().collect()),
                    Err(uref) => Ok(vec![uref]),
                }
            }
            (CLType::Key, CLType::Key) => {
                let res: Result<Key, Key> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(key) => Ok(key.into_uref().into_iter().collect()),
                    Err(key) => Ok(key.into_uref().into_iter().collect()),
                }
            }
            (CLType::Bool, CLType::URef) => {
                let res: Result<bool, URef> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(uref) => Ok(vec![uref]),
                }
            }
            (CLType::I32, CLType::URef) => {
                let res: Result<i32, URef> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(uref) => Ok(vec![uref]),
                }
            }
            (CLType::I64, CLType::URef) => {
                let res: Result<i64, URef> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(uref) => Ok(vec![uref]),
                }
            }
            (CLType::U8, CLType::URef) => {
                let res: Result<u8, URef> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(uref) => Ok(vec![uref]),
                }
            }
            (CLType::U32, CLType::URef) => {
                let res: Result<u32, URef> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(uref) => Ok(vec![uref]),
                }
            }
            (CLType::U64, CLType::URef) => {
                let res: Result<u64, URef> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(uref) => Ok(vec![uref]),
                }
            }
            (CLType::U128, CLType::URef) => {
                let res: Result<U128, URef> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(uref) => Ok(vec![uref]),
                }
            }
            (CLType::U256, CLType::URef) => {
                let res: Result<U256, URef> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(uref) => Ok(vec![uref]),
                }
            }
            (CLType::U512, CLType::URef) => {
                let res: Result<U512, URef> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(uref) => Ok(vec![uref]),
                }
            }
            (CLType::Unit, CLType::URef) => {
                let res: Result<(), URef> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(uref) => Ok(vec![uref]),
                }
            }
            (CLType::String, CLType::URef) => {
                let res: Result<String, URef> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(uref) => Ok(vec![uref]),
                }
            }
            (CLType::Bool, CLType::Key) => {
                let res: Result<bool, Key> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(key) => Ok(key.into_uref().into_iter().collect()),
                }
            }
            (CLType::I32, CLType::Key) => {
                let res: Result<i32, Key> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(key) => Ok(key.into_uref().into_iter().collect()),
                }
            }
            (CLType::I64, CLType::Key) => {
                let res: Result<i64, Key> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(key) => Ok(key.into_uref().into_iter().collect()),
                }
            }
            (CLType::U8, CLType::Key) => {
                let res: Result<u8, Key> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(key) => Ok(key.into_uref().into_iter().collect()),
                }
            }
            (CLType::U32, CLType::Key) => {
                let res: Result<u32, Key> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(key) => Ok(key.into_uref().into_iter().collect()),
                }
            }
            (CLType::U64, CLType::Key) => {
                let res: Result<u64, Key> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(key) => Ok(key.into_uref().into_iter().collect()),
                }
            }
            (CLType::U128, CLType::Key) => {
                let res: Result<U128, Key> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(key) => Ok(key.into_uref().into_iter().collect()),
                }
            }
            (CLType::U256, CLType::Key) => {
                let res: Result<U256, Key> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(key) => Ok(key.into_uref().into_iter().collect()),
                }
            }
            (CLType::U512, CLType::Key) => {
                let res: Result<U512, Key> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(key) => Ok(key.into_uref().into_iter().collect()),
                }
            }
            (CLType::Unit, CLType::Key) => {
                let res: Result<(), Key> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(key) => Ok(key.into_uref().into_iter().collect()),
                }
            }
            (CLType::String, CLType::Key) => {
                let res: Result<String, Key> = cl_value.to_owned().into_t()?;
                match res {
                    Ok(_) => Ok(vec![]),
                    Err(key) => Ok(key.into_uref().into_iter().collect()),
                }
            }
            (_, _) => Ok(vec![]),
        },
        CLType::Map { key, value } => match (&**key, &**value) {
            (CLType::URef, CLType::Bool) => {
                let map: BTreeMap<URef, bool> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().collect())
            }
            (CLType::URef, CLType::I32) => {
                let map: BTreeMap<URef, i32> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().collect())
            }
            (CLType::URef, CLType::I64) => {
                let map: BTreeMap<URef, i64> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().collect())
            }
            (CLType::URef, CLType::U8) => {
                let map: BTreeMap<URef, u8> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().collect())
            }
            (CLType::URef, CLType::U32) => {
                let map: BTreeMap<URef, u32> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().collect())
            }
            (CLType::URef, CLType::U64) => {
                let map: BTreeMap<URef, u64> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().collect())
            }
            (CLType::URef, CLType::U128) => {
                let map: BTreeMap<URef, U128> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().collect())
            }
            (CLType::URef, CLType::U256) => {
                let map: BTreeMap<URef, U256> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().collect())
            }
            (CLType::URef, CLType::U512) => {
                let map: BTreeMap<URef, U512> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().collect())
            }
            (CLType::URef, CLType::Unit) => {
                let map: BTreeMap<URef, ()> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().collect())
            }
            (CLType::URef, CLType::String) => {
                let map: BTreeMap<URef, String> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().collect())
            }
            (CLType::URef, CLType::Key) => {
                let map: BTreeMap<URef, Key> = cl_value.to_owned().into_t()?;
                Ok(map
                    .keys()
                    .cloned()
                    .chain(map.values().cloned().filter_map(Key::into_uref))
                    .collect())
            }
            (CLType::URef, CLType::URef) => {
                let map: BTreeMap<URef, URef> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().chain(map.values().cloned()).collect())
            }
            (CLType::Key, CLType::Bool) => {
                let map: BTreeMap<Key, bool> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::Key, CLType::I32) => {
                let map: BTreeMap<Key, i32> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::Key, CLType::I64) => {
                let map: BTreeMap<Key, i64> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::Key, CLType::U8) => {
                let map: BTreeMap<Key, u8> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::Key, CLType::U32) => {
                let map: BTreeMap<Key, u32> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::Key, CLType::U64) => {
                let map: BTreeMap<Key, u64> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::Key, CLType::U128) => {
                let map: BTreeMap<Key, U128> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::Key, CLType::U256) => {
                let map: BTreeMap<Key, U256> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::Key, CLType::U512) => {
                let map: BTreeMap<Key, U512> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::Key, CLType::Unit) => {
                let map: BTreeMap<Key, ()> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::Key, CLType::String) => {
                let map: BTreeMap<Key, String> = cl_value.to_owned().into_t()?;
                Ok(map.keys().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::Key, CLType::URef) => {
                let map: BTreeMap<Key, URef> = cl_value.to_owned().into_t()?;
                Ok(map
                    .keys()
                    .cloned()
                    .filter_map(Key::into_uref)
                    .chain(map.values().cloned())
                    .collect())
            }
            (CLType::Key, CLType::Key) => {
                let map: BTreeMap<Key, Key> = cl_value.to_owned().into_t()?;
                Ok(map
                    .keys()
                    .cloned()
                    .filter_map(Key::into_uref)
                    .chain(map.values().cloned().filter_map(Key::into_uref))
                    .collect())
            }
            (CLType::Bool, CLType::URef) => {
                let map: BTreeMap<bool, URef> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().collect())
            }
            (CLType::I32, CLType::URef) => {
                let map: BTreeMap<i32, URef> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().collect())
            }
            (CLType::I64, CLType::URef) => {
                let map: BTreeMap<i64, URef> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().collect())
            }
            (CLType::U8, CLType::URef) => {
                let map: BTreeMap<u8, URef> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().collect())
            }
            (CLType::U32, CLType::URef) => {
                let map: BTreeMap<u32, URef> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().collect())
            }
            (CLType::U64, CLType::URef) => {
                let map: BTreeMap<u64, URef> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().collect())
            }
            (CLType::U128, CLType::URef) => {
                let map: BTreeMap<U128, URef> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().collect())
            }
            (CLType::U256, CLType::URef) => {
                let map: BTreeMap<U256, URef> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().collect())
            }
            (CLType::U512, CLType::URef) => {
                let map: BTreeMap<U512, URef> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().collect())
            }
            (CLType::Unit, CLType::URef) => {
                let map: BTreeMap<(), URef> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().collect())
            }
            (CLType::String, CLType::URef) => {
                let map: BTreeMap<String, URef> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().collect())
            }
            (CLType::Bool, CLType::Key) => {
                let map: BTreeMap<bool, Key> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::I32, CLType::Key) => {
                let map: BTreeMap<i32, Key> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::I64, CLType::Key) => {
                let map: BTreeMap<i64, Key> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::U8, CLType::Key) => {
                let map: BTreeMap<u8, Key> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::U32, CLType::Key) => {
                let map: BTreeMap<u32, Key> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::U64, CLType::Key) => {
                let map: BTreeMap<u64, Key> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::U128, CLType::Key) => {
                let map: BTreeMap<U128, Key> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::U256, CLType::Key) => {
                let map: BTreeMap<U256, Key> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::U512, CLType::Key) => {
                let map: BTreeMap<U512, Key> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::Unit, CLType::Key) => {
                let map: BTreeMap<(), Key> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().filter_map(Key::into_uref).collect())
            }
            (CLType::String, CLType::Key) => {
                let map: BTreeMap<String, Key> = cl_value.to_owned().into_t()?;
                Ok(map.values().cloned().filter_map(Key::into_uref).collect())
            }
            (_, _) => Ok(vec![]),
        },
        CLType::Tuple1([ty]) => match **ty {
            CLType::URef => {
                let val: (URef,) = cl_value.to_owned().into_t()?;
                Ok(vec![val.0])
            }
            CLType::Key => {
                let val: (Key,) = cl_value.to_owned().into_t()?;
                Ok(val.0.into_uref().into_iter().collect())
            }
            _ => Ok(vec![]),
        },
        CLType::Tuple2([ty1, ty2]) => match (&**ty1, &**ty2) {
            (CLType::URef, CLType::Bool) => {
                let val: (URef, bool) = cl_value.to_owned().into_t()?;
                Ok(vec![val.0])
            }
            (CLType::URef, CLType::I32) => {
                let val: (URef, i32) = cl_value.to_owned().into_t()?;
                Ok(vec![val.0])
            }
            (CLType::URef, CLType::I64) => {
                let val: (URef, i64) = cl_value.to_owned().into_t()?;
                Ok(vec![val.0])
            }
            (CLType::URef, CLType::U8) => {
                let val: (URef, u8) = cl_value.to_owned().into_t()?;
                Ok(vec![val.0])
            }
            (CLType::URef, CLType::U32) => {
                let val: (URef, u32) = cl_value.to_owned().into_t()?;
                Ok(vec![val.0])
            }
            (CLType::URef, CLType::U64) => {
                let val: (URef, u64) = cl_value.to_owned().into_t()?;
                Ok(vec![val.0])
            }
            (CLType::URef, CLType::U128) => {
                let val: (URef, U128) = cl_value.to_owned().into_t()?;
                Ok(vec![val.0])
            }
            (CLType::URef, CLType::U256) => {
                let val: (URef, U256) = cl_value.to_owned().into_t()?;
                Ok(vec![val.0])
            }
            (CLType::URef, CLType::U512) => {
                let val: (URef, U512) = cl_value.to_owned().into_t()?;
                Ok(vec![val.0])
            }
            (CLType::URef, CLType::Unit) => {
                let val: (URef, ()) = cl_value.to_owned().into_t()?;
                Ok(vec![val.0])
            }
            (CLType::URef, CLType::String) => {
                let val: (URef, String) = cl_value.to_owned().into_t()?;
                Ok(vec![val.0])
            }
            (CLType::URef, CLType::Key) => {
                let val: (URef, Key) = cl_value.to_owned().into_t()?;
                let mut res = vec![val.0];
                res.extend(val.1.into_uref().into_iter());
                Ok(res)
            }
            (CLType::URef, CLType::URef) => {
                let val: (URef, URef) = cl_value.to_owned().into_t()?;
                Ok(vec![val.0, val.1])
            }
            (CLType::Key, CLType::Bool) => {
                let val: (Key, bool) = cl_value.to_owned().into_t()?;
                Ok(val.0.into_uref().into_iter().collect())
            }
            (CLType::Key, CLType::I32) => {
                let val: (Key, i32) = cl_value.to_owned().into_t()?;
                Ok(val.0.into_uref().into_iter().collect())
            }
            (CLType::Key, CLType::I64) => {
                let val: (Key, i64) = cl_value.to_owned().into_t()?;
                Ok(val.0.into_uref().into_iter().collect())
            }
            (CLType::Key, CLType::U8) => {
                let val: (Key, u8) = cl_value.to_owned().into_t()?;
                Ok(val.0.into_uref().into_iter().collect())
            }
            (CLType::Key, CLType::U32) => {
                let val: (Key, u32) = cl_value.to_owned().into_t()?;
                Ok(val.0.into_uref().into_iter().collect())
            }
            (CLType::Key, CLType::U64) => {
                let val: (Key, u64) = cl_value.to_owned().into_t()?;
                Ok(val.0.into_uref().into_iter().collect())
            }
            (CLType::Key, CLType::U128) => {
                let val: (Key, U128) = cl_value.to_owned().into_t()?;
                Ok(val.0.into_uref().into_iter().collect())
            }
            (CLType::Key, CLType::U256) => {
                let val: (Key, U256) = cl_value.to_owned().into_t()?;
                Ok(val.0.into_uref().into_iter().collect())
            }
            (CLType::Key, CLType::U512) => {
                let val: (Key, U512) = cl_value.to_owned().into_t()?;
                Ok(val.0.into_uref().into_iter().collect())
            }
            (CLType::Key, CLType::Unit) => {
                let val: (Key, ()) = cl_value.to_owned().into_t()?;
                Ok(val.0.into_uref().into_iter().collect())
            }
            (CLType::Key, CLType::String) => {
                let val: (Key, String) = cl_value.to_owned().into_t()?;
                Ok(val.0.into_uref().into_iter().collect())
            }
            (CLType::Key, CLType::URef) => {
                let val: (Key, URef) = cl_value.to_owned().into_t()?;
                let mut res: Vec<URef> = val.0.into_uref().into_iter().collect();
                res.push(val.1);
                Ok(res)
            }
            (CLType::Key, CLType::Key) => {
                let val: (Key, Key) = cl_value.to_owned().into_t()?;
                Ok(val
                    .0
                    .into_uref()
                    .into_iter()
                    .chain(val.1.into_uref().into_iter())
                    .collect())
            }
            (CLType::Bool, CLType::URef) => {
                let val: (bool, URef) = cl_value.to_owned().into_t()?;
                Ok(vec![val.1])
            }
            (CLType::I32, CLType::URef) => {
                let val: (i32, URef) = cl_value.to_owned().into_t()?;
                Ok(vec![val.1])
            }
            (CLType::I64, CLType::URef) => {
                let val: (i64, URef) = cl_value.to_owned().into_t()?;
                Ok(vec![val.1])
            }
            (CLType::U8, CLType::URef) => {
                let val: (u8, URef) = cl_value.to_owned().into_t()?;
                Ok(vec![val.1])
            }
            (CLType::U32, CLType::URef) => {
                let val: (u32, URef) = cl_value.to_owned().into_t()?;
                Ok(vec![val.1])
            }
            (CLType::U64, CLType::URef) => {
                let val: (u64, URef) = cl_value.to_owned().into_t()?;
                Ok(vec![val.1])
            }
            (CLType::U128, CLType::URef) => {
                let val: (U128, URef) = cl_value.to_owned().into_t()?;
                Ok(vec![val.1])
            }
            (CLType::U256, CLType::URef) => {
                let val: (U256, URef) = cl_value.to_owned().into_t()?;
                Ok(vec![val.1])
            }
            (CLType::U512, CLType::URef) => {
                let val: (U512, URef) = cl_value.to_owned().into_t()?;
                Ok(vec![val.1])
            }
            (CLType::Unit, CLType::URef) => {
                let val: ((), URef) = cl_value.to_owned().into_t()?;
                Ok(vec![val.1])
            }
            (CLType::String, CLType::URef) => {
                let val: (String, URef) = cl_value.to_owned().into_t()?;
                Ok(vec![val.1])
            }
            (CLType::Bool, CLType::Key) => {
                let val: (bool, Key) = cl_value.to_owned().into_t()?;
                Ok(val.1.into_uref().into_iter().collect())
            }
            (CLType::I32, CLType::Key) => {
                let val: (i32, Key) = cl_value.to_owned().into_t()?;
                Ok(val.1.into_uref().into_iter().collect())
            }
            (CLType::I64, CLType::Key) => {
                let val: (i64, Key) = cl_value.to_owned().into_t()?;
                Ok(val.1.into_uref().into_iter().collect())
            }
            (CLType::U8, CLType::Key) => {
                let val: (u8, Key) = cl_value.to_owned().into_t()?;
                Ok(val.1.into_uref().into_iter().collect())
            }
            (CLType::U32, CLType::Key) => {
                let val: (u32, Key) = cl_value.to_owned().into_t()?;
                Ok(val.1.into_uref().into_iter().collect())
            }
            (CLType::U64, CLType::Key) => {
                let val: (u64, Key) = cl_value.to_owned().into_t()?;
                Ok(val.1.into_uref().into_iter().collect())
            }
            (CLType::U128, CLType::Key) => {
                let val: (U128, Key) = cl_value.to_owned().into_t()?;
                Ok(val.1.into_uref().into_iter().collect())
            }
            (CLType::U256, CLType::Key) => {
                let val: (U256, Key) = cl_value.to_owned().into_t()?;
                Ok(val.1.into_uref().into_iter().collect())
            }
            (CLType::U512, CLType::Key) => {
                let val: (U512, Key) = cl_value.to_owned().into_t()?;
                Ok(val.1.into_uref().into_iter().collect())
            }
            (CLType::Unit, CLType::Key) => {
                let val: ((), Key) = cl_value.to_owned().into_t()?;
                Ok(val.1.into_uref().into_iter().collect())
            }
            (CLType::String, CLType::Key) => {
                let val: (String, Key) = cl_value.to_owned().into_t()?;
                Ok(val.1.into_uref().into_iter().collect())
            }
            (_, _) => Ok(vec![]),
        },
        // TODO: nested matches for Tuple3?
        CLType::Tuple3(_) => Ok(vec![]),
        CLType::Key => {
            let key: Key = cl_value.to_owned().into_t()?; // TODO: optimize?
            Ok(key.into_uref().into_iter().collect())
        }
        CLType::URef => {
            let uref: URef = cl_value.to_owned().into_t()?; // TODO: optimize?
            Ok(vec![uref])
        }
    }
}

fn sub_call<R>(
    parity_module: Module,
    args: Vec<CLValue>,
    named_keys: &mut BTreeMap<String, Key>,
    key: Key,
    current_runtime: &mut Runtime<R>,
    // Unforgable references passed across the call boundary from caller to callee (necessary if
    // the contract takes a uref argument).
    extra_urefs: Vec<Key>,
    protocol_version: ProtocolVersion,
) -> Result<CLValue, Error>
where
    R: StateReader<Key, StoredValue>,
    R::Error: Into<Error>,
{
    let (instance, memory) = instance_and_memory(parity_module.clone(), protocol_version)?;

    let access_rights = {
        let mut keys: Vec<Key> = named_keys.values().cloned().collect();
        keys.extend(extra_urefs);
        keys.push(current_runtime.get_mint_contract_uref().into());
        keys.push(current_runtime.get_pos_contract_uref().into());
        extract_access_rights_from_keys(keys)
    };

    let system_contract_cache = SystemContractCache::clone(&current_runtime.system_contract_cache);

    let mut runtime = Runtime {
        system_contract_cache,
        memory,
        module: parity_module,
        host_buf: None,
        context: RuntimeContext::new(
            current_runtime.context.state(),
            named_keys,
            access_rights,
            args,
            current_runtime.context.authorization_keys().clone(),
            &current_runtime.context.account(),
            key,
            current_runtime.context.get_blocktime(),
            current_runtime.context.get_deployhash(),
            current_runtime.context.gas_limit(),
            current_runtime.context.gas_counter(),
            current_runtime.context.fn_store_id(),
            current_runtime.context.address_generator(),
            protocol_version,
            current_runtime.context.correlation_id(),
            current_runtime.context.phase(),
            current_runtime.context.protocol_data(),
        ),
    };

    let result = instance.invoke_export("call", &[], &mut runtime);

    // TODO: To account for the gas used in a subcall, we should uncomment the following lines
    // if !current_runtime.charge_gas(runtime.context.gas_counter()) {
    //     return Err(Error::GasLimit);
    // }

    match result {
        // If `Ok` and the `host_buf` is `None`, the contract's execution succeeded but did not
        // explicitly call `runtime::ret()`.  Treat as though the execution returned the unit type
        // `()` as per Rust functions which don't specify a return value.
        Ok(_) => Ok(runtime.take_host_buf().unwrap_or(CLValue::from_t(())?)),
        Err(e) => {
            if let Some(host_error) = e.as_host_error() {
                // If the "error" was in fact a trap caused by calling `ret` then
                // this is normal operation and we should return the value captured
                // in the Runtime result field.
                let downcasted_error = host_error.downcast_ref::<Error>().unwrap();
                match downcasted_error {
                    Error::Ret(ref ret_urefs) => {
                        //insert extra urefs returned from call
                        let ret_urefs_map: HashMap<Address, HashSet<AccessRights>> =
                            extract_access_rights_from_urefs(ret_urefs.clone());
                        current_runtime.context.access_rights_extend(ret_urefs_map);
                        // if ret has not set host_buf consider it programmer error
                        return runtime.take_host_buf().ok_or(Error::ExpectedReturnValue);
                    }
                    Error::Revert(status) => {
                        // Propagate revert as revert, instead of passing it as
                        // InterpreterError.
                        return Err(Error::Revert(*status));
                    }
                    Error::InvalidContext => {
                        // TODO: https://casperlabs.atlassian.net/browse/EE-771
                        return Err(Error::InvalidContext);
                    }
                    _ => {}
                }
            }
            Err(Error::Interpreter(e))
        }
    }
}

impl<'a, R> Runtime<'a, R>
where
    R: StateReader<Key, StoredValue>,
    R::Error: Into<Error>,
{
    pub fn new(
        system_contract_cache: SystemContractCache,
        memory: MemoryRef,
        module: Module,
        context: RuntimeContext<'a, R>,
    ) -> Self {
        Runtime {
            system_contract_cache,
            memory,
            module,
            host_buf: None,
            context,
        }
    }

    pub fn context(&self) -> &RuntimeContext<'a, R> {
        &self.context
    }

    /// Charge specified amount of gas
    ///
    /// Returns false if gas limit exceeded and true if not.
    /// Intuition about the return value sense is to answer the question 'are we
    /// allowed to continue?'
    fn charge_gas(&mut self, amount: Gas) -> bool {
        let prev = self.context.gas_counter();
        match prev.checked_add(amount) {
            // gas charge overflow protection
            None => false,
            Some(val) if val > self.context.gas_limit() => false,
            Some(val) => {
                self.context.set_gas_counter(val);
                true
            }
        }
    }

    fn gas(&mut self, amount: Gas) -> Result<(), Trap> {
        if self.charge_gas(amount) {
            Ok(())
        } else {
            Err(Error::GasLimit.into())
        }
    }

    fn bytes_from_mem(&self, ptr: u32, size: usize) -> Result<Vec<u8>, Error> {
        self.memory.get(ptr, size).map_err(Into::into)
    }

    /// Reads key (defined as `key_ptr` and `key_size` tuple) from Wasm memory.
    fn key_from_mem(&mut self, key_ptr: u32, key_size: u32) -> Result<Key, Error> {
        let bytes = self.bytes_from_mem(key_ptr, key_size as usize)?;
        bytesrepr::deserialize(bytes).map_err(Into::into)
    }

    /// Reads `CLValue` (defined as `cl_value_ptr` and `cl_value_size` tuple) from Wasm memory.
    fn cl_value_from_mem(
        &mut self,
        cl_value_ptr: u32,
        cl_value_size: u32,
    ) -> Result<CLValue, Error> {
        let bytes = self.bytes_from_mem(cl_value_ptr, cl_value_size as usize)?;
        bytesrepr::deserialize(bytes).map_err(Into::into)
    }

    fn string_from_mem(&self, ptr: u32, size: u32) -> Result<String, Trap> {
        let bytes = self.bytes_from_mem(ptr, size as usize)?;
        bytesrepr::deserialize(bytes).map_err(|e| Error::BytesRepr(e).into())
    }

    fn get_function_by_name(&mut self, name_ptr: u32, name_size: u32) -> Result<Vec<u8>, Trap> {
        let name = self.string_from_mem(name_ptr, name_size)?;

        let has_name: bool = self
            .module
            .export_section()
            .and_then(|export_section| {
                export_section
                    .entries()
                    .iter()
                    .find(|export_entry| export_entry.field() == name)
            })
            .is_some();

        if has_name {
            let mut module = self.module.clone();
            // We only want the function exported under `name` to be callable; `optimize` removes
            // all code that is not reachable from the exports listed in the second argument.
            pwasm_utils::optimize(&mut module, vec![&name]).unwrap();
            rename_export_to_call(&mut module, name);

            parity_wasm::serialize(module).map_err(|e| Error::ParityWasm(e).into())
        } else {
            Err(Error::FunctionNotFound(name).into())
        }
    }

    fn is_valid_uref(&mut self, uref_ptr: u32, uref_size: u32) -> Result<bool, Trap> {
        let bytes = self.bytes_from_mem(uref_ptr, uref_size as usize)?;
        let uref: URef = bytesrepr::deserialize(bytes).map_err(Error::BytesRepr)?;
        let key = Key::URef(uref);
        Ok(self.context.validate_key(&key).is_ok())
    }

    fn get_arg_size(&mut self, index: usize, size_ptr: u32) -> Result<Result<(), ApiError>, Trap> {
        let arg_size = match self.context.args().get(index) {
            Some(arg) if arg.inner_bytes().len() > u32::max_value() as usize => {
                return Ok(Err(ApiError::OutOfMemoryError))
            }
            None => return Ok(Err(ApiError::MissingArgument)),
            Some(arg) => arg.inner_bytes().len() as u32,
        };

        let arg_size_bytes = arg_size.to_le_bytes(); // Wasm is little-endian

        if let Err(e) = self.memory.set(size_ptr, &arg_size_bytes) {
            return Err(Error::Interpreter(e).into());
        }

        Ok(Ok(()))
    }

    fn get_arg(
        &mut self,
        index: usize,
        output_ptr: u32,
        output_size: usize,
    ) -> Result<Result<(), ApiError>, Trap> {
        let arg = match self.context.args().get(index) {
            Some(arg) => arg,
            None => return Ok(Err(ApiError::MissingArgument)),
        };

        if arg.inner_bytes().len() > output_size {
            return Ok(Err(ApiError::OutOfMemoryError));
        }

        if let Err(e) = self
            .memory
            .set(output_ptr, &arg.inner_bytes()[..output_size])
        {
            return Err(Error::Interpreter(e).into());
        }

        Ok(Ok(()))
    }

    /// Load the uref known by the given name into the Wasm memory
    fn load_key(
        &mut self,
        name_ptr: u32,
        name_size: u32,
        output_ptr: u32,
        output_size: usize,
        bytes_written_ptr: u32,
    ) -> Result<Result<(), ApiError>, Trap> {
        let name = self.string_from_mem(name_ptr, name_size)?;

        // Get a key and serialize it
        let key = match self.context.named_keys_get(&name) {
            Some(key) => key,
            None => return Ok(Err(ApiError::MissingKey)),
        };

        let key_bytes = match key.to_bytes() {
            Ok(bytes) => bytes,
            Err(error) => return Ok(Err(error.into())),
        };

        // `output_size` has to be greater or equal to the actual length of serialized Key bytes
        if output_size < key_bytes.len() {
            return Ok(Err(ApiError::BufferTooSmall));
        }

        // Set serialized Key bytes into the output buffer
        if let Err(error) = self.memory.set(output_ptr, &key_bytes) {
            return Err(Error::Interpreter(error).into());
        }

        // For all practical purposes following cast is assumed to be safe
        let bytes_size = key_bytes.len() as u32;
        let size_bytes = bytes_size.to_le_bytes(); // Wasm is little-endian
        if let Err(error) = self.memory.set(bytes_written_ptr, &size_bytes) {
            return Err(Error::Interpreter(error).into());
        }

        Ok(Ok(()))
    }

    fn has_key(&mut self, name_ptr: u32, name_size: u32) -> Result<i32, Trap> {
        let name = self.string_from_mem(name_ptr, name_size)?;
        if self.context.named_keys_contains_key(&name) {
            Ok(0)
        } else {
            Ok(1)
        }
    }

    fn put_key(
        &mut self,
        name_ptr: u32,
        name_size: u32,
        key_ptr: u32,
        key_size: u32,
    ) -> Result<(), Trap> {
        let name = self.string_from_mem(name_ptr, name_size)?;
        let key = self.key_from_mem(key_ptr, key_size)?;
        self.context.put_key(name, key).map_err(Into::into)
    }

    fn remove_key(&mut self, name_ptr: u32, name_size: u32) -> Result<(), Trap> {
        let name = self.string_from_mem(name_ptr, name_size)?;
        self.context.remove_key(&name)?;
        Ok(())
    }

    /// Writes runtime context's account main purse to [dest_ptr] in the Wasm memory.
    fn get_main_purse(&mut self, dest_ptr: u32) -> Result<(), Trap> {
        let purse_id = self.context.get_main_purse()?;
        let purse_id_bytes = purse_id.into_bytes().map_err(Error::BytesRepr)?;
        self.memory
            .set(dest_ptr, &purse_id_bytes)
            .map_err(|e| Error::Interpreter(e).into())
    }

    /// Writes caller (deploy) account public key to [dest_ptr] in the Wasm
    /// memory.
    fn get_caller(&mut self, dest_ptr: u32) -> Result<(), Trap> {
        let key = self.context.get_caller();
        let bytes = key.into_bytes().map_err(Error::BytesRepr)?;
        self.memory
            .set(dest_ptr, &bytes)
            .map_err(|e| Error::Interpreter(e).into())
    }

    /// Writes runtime context's phase to [dest_ptr] in the Wasm memory.
    fn get_phase(&mut self, dest_ptr: u32) -> Result<(), Trap> {
        let phase = self.context.phase();
        let bytes = phase.into_bytes().map_err(Error::BytesRepr)?;
        self.memory
            .set(dest_ptr, &bytes)
            .map_err(|e| Error::Interpreter(e).into())
    }

    /// Writes current blocktime to [dest_ptr] in Wasm memory.
    fn get_blocktime(&self, dest_ptr: u32) -> Result<(), Trap> {
        let blocktime = self
            .context
            .get_blocktime()
            .into_bytes()
            .map_err(Error::BytesRepr)?;
        self.memory
            .set(dest_ptr, &blocktime)
            .map_err(|e| Error::Interpreter(e).into())
    }

    /// Return some bytes from the memory and terminate the current `sub_call`. Note that the return
    /// type is `Trap`, indicating that this function will always kill the current Wasm instance.
    fn ret(&mut self, value_ptr: u32, value_size: usize) -> Trap {
        self.host_buf = None;
        let mem_get = self
            .memory
            .get(value_ptr, value_size)
            .map_err(Error::Interpreter);
        match mem_get {
            Ok(buf) => {
                // Set the result field in the runtime and return the proper element of the `Error`
                // enum indicating that the reason for exiting the module was a call to ret.
                self.host_buf = bytesrepr::deserialize(buf).ok();

                let urefs = match &self.host_buf {
                    Some(buf) => extract_urefs(buf),
                    None => Ok(vec![]),
                };
                match urefs {
                    Ok(urefs) => Error::Ret(urefs).into(),
                    Err(e) => e.into(),
                }
            }
            Err(e) => e.into(),
        }
    }

    /// Calls contract living under a `key`, with supplied `args`.
    fn call_contract(&mut self, key: Key, args_bytes: Vec<u8>) -> Result<CLValue, Error> {
        let contract = match self.context.read_gs(&key)? {
            Some(StoredValue::Contract(contract)) => contract,
            Some(_) => {
                return Err(Error::FunctionNotFound(format!(
                    "Value at {:?} is not a contract",
                    key
                )))
            }
            None => return Err(Error::KeyNotFound(key)),
        };

        // Check for major version compatibility before calling
        let contract_version = contract.protocol_version();
        let current_version = self.context.protocol_version();
        if !contract_version.is_compatible_with(&current_version) {
            return Err(Error::IncompatibleProtocolMajorVersion {
                actual: current_version.value().major,
                expected: contract_version.value().major,
            });
        }

        let args: Vec<CLValue> = bytesrepr::deserialize(args_bytes)?;

        let maybe_module = match key {
            Key::URef(uref) => self.system_contract_cache.get(&uref),
            _ => None,
        };

        let module = match maybe_module {
            Some(module) => module,
            None => parity_wasm::deserialize_buffer(contract.bytes())?,
        };

        let mut extra_urefs = vec![];
        // A loop is needed to be able to use the '?' operator
        for arg in &args {
            extra_urefs.extend(
                extract_urefs(arg)?
                    .into_iter()
                    .map(<Key as From<URef>>::from),
            );
        }
        for key in &extra_urefs {
            self.context.validate_key(key)?;
        }

        let mut refs = contract.take_named_keys();

        let result = sub_call(
            module,
            args,
            &mut refs,
            key,
            self,
            extra_urefs,
            contract_version,
        )?;
        Ok(result)
    }

    fn call_contract_host_buf(
        &mut self,
        key: Key,
        args_bytes: Vec<u8>,
        result_size_ptr: u32,
    ) -> Result<Result<(), ApiError>, Error> {
        if !self.can_write_to_host_buf() {
            // Exit early if the host buffer is already occupied
            return Ok(Err(ApiError::HostBufferFull));
        }

        let result = self.call_contract(key, args_bytes)?;
        let result_size = result.inner_bytes().len() as u32; // considered to be safe

        if let Err(error) = self.write_host_buf(result) {
            return Ok(Err(error));
        }

        let result_size_bytes = result_size.to_le_bytes(); // Wasm is little-endian
        if let Err(error) = self.memory.set(result_size_ptr, &result_size_bytes) {
            return Err(Error::Interpreter(error));
        }

        Ok(Ok(()))
    }

    fn load_named_keys(
        &mut self,
        total_keys_ptr: u32,
        result_size_ptr: u32,
    ) -> Result<Result<(), ApiError>, Trap> {
        if !self.can_write_to_host_buf() {
            // Exit early if the host buffer is already occupied
            return Ok(Err(ApiError::HostBufferFull));
        }

        let total_keys = self.context.named_keys().len() as u32;
        let total_keys_bytes = total_keys.to_le_bytes();
        if let Err(error) = self.memory.set(total_keys_ptr, &total_keys_bytes) {
            return Err(Error::Interpreter(error).into());
        }

        if total_keys == 0 {
            // No need to do anything else, we leave host buffer empty.
            return Ok(Ok(()));
        }

        let named_keys =
            CLValue::from_t(self.context.named_keys().clone()).map_err(Error::CLValue)?;

        let length = named_keys.inner_bytes().len() as u32;
        if let Err(error) = self.write_host_buf(named_keys) {
            return Ok(Err(error));
        }

        let length_bytes = length.to_le_bytes();
        if let Err(error) = self.memory.set(result_size_ptr, &length_bytes) {
            return Err(Error::Interpreter(error).into());
        }

        Ok(Ok(()))
    }

    fn store_function(
        &mut self,
        fn_bytes: Vec<u8>,
        named_keys: BTreeMap<String, Key>,
    ) -> Result<[u8; 32], Error> {
        let contract = Contract::new(fn_bytes, named_keys, self.context.protocol_version());
        let contract_addr = self
            .context
            .store_function(StoredValue::Contract(contract))?;
        Ok(contract_addr)
    }

    /// Tries to store a function, represented as bytes from the Wasm memory,
    /// into the GlobalState and writes back a function's hash at `hash_ptr`
    /// in the Wasm memory.
    fn store_function_at_hash(
        &mut self,
        fn_bytes: Vec<u8>,
        named_keys: BTreeMap<String, Key>,
    ) -> Result<[u8; 32], Error> {
        let contract = Contract::new(fn_bytes, named_keys, self.context.protocol_version());
        let new_hash = self
            .context
            .store_function_at_hash(StoredValue::Contract(contract))?;
        Ok(new_hash)
    }

    /// Writes function address (`hash_bytes`) into the Wasm memory (at
    /// `dest_ptr` pointer).
    fn function_address(&mut self, hash_bytes: [u8; 32], dest_ptr: u32) -> Result<(), Trap> {
        self.memory
            .set(dest_ptr, &hash_bytes)
            .map_err(|e| Error::Interpreter(e).into())
    }

    /// Generates new unforgable reference and adds it to the context's
    /// access_rights set.
    fn new_uref(&mut self, key_ptr: u32, value_ptr: u32, value_size: u32) -> Result<(), Trap> {
        let cl_value = self.cl_value_from_mem(value_ptr, value_size)?; // read initial value from memory
        let key = self.context.new_uref(StoredValue::CLValue(cl_value))?;
        self.memory
            .set(key_ptr, &key.into_bytes().map_err(Error::BytesRepr)?)
            .map_err(|e| Error::Interpreter(e).into())
    }

    /// Writes `value` under `key` in GlobalState.
    fn write(
        &mut self,
        key_ptr: u32,
        key_size: u32,
        value_ptr: u32,
        value_size: u32,
    ) -> Result<(), Trap> {
        let key = self.key_from_mem(key_ptr, key_size)?;
        let cl_value = self.cl_value_from_mem(value_ptr, value_size)?;
        self.context
            .write_gs(key, StoredValue::CLValue(cl_value))
            .map_err(Into::into)
    }

    /// Writes `value` under a key derived from `key` in the "local cluster" of
    /// GlobalState
    fn write_local(
        &mut self,
        key_ptr: u32,
        key_size: u32,
        value_ptr: u32,
        value_size: u32,
    ) -> Result<(), Trap> {
        let key_bytes = self.bytes_from_mem(key_ptr, key_size as usize)?;
        let cl_value = self.cl_value_from_mem(value_ptr, value_size)?;
        self.context
            .write_ls(&key_bytes, cl_value)
            .map_err(Into::into)
    }

    /// Adds `value` to the cell that `key` points at.
    fn add(
        &mut self,
        key_ptr: u32,
        key_size: u32,
        value_ptr: u32,
        value_size: u32,
    ) -> Result<(), Trap> {
        let key = self.key_from_mem(key_ptr, key_size)?;
        let cl_value = self.cl_value_from_mem(value_ptr, value_size)?;
        self.context
            .add_gs(key, StoredValue::CLValue(cl_value))
            .map_err(Into::into)
    }

    /// Adds `value` to the cell pointed to by a key derived from `key` in the "local cluster" of
    /// GlobalState
    pub fn add_local(
        &mut self,
        key_ptr: u32,
        key_size: u32,
        value_ptr: u32,
        value_size: u32,
    ) -> Result<(), Trap> {
        let key_bytes = self.bytes_from_mem(key_ptr, key_size as usize)?;
        let cl_value = self.cl_value_from_mem(value_ptr, value_size)?;
        self.context
            .add_ls(&key_bytes, cl_value)
            .map_err(Into::into)
    }

    /// Reads value from the GS living under key specified by `key_ptr` and
    /// `key_size`. Wasm and host communicate through memory that Wasm
    /// module exports. If contract wants to pass data to the host, it has
    /// to tell it [the host] where this data lives in the exported memory
    /// (pass its pointer and length).
    fn read(
        &mut self,
        key_ptr: u32,
        key_size: u32,
        output_size_ptr: u32,
    ) -> Result<Result<(), ApiError>, Trap> {
        if !self.can_write_to_host_buf() {
            // Exit early if the host buffer is already occupied
            return Ok(Err(ApiError::HostBufferFull));
        }

        let key = self.key_from_mem(key_ptr, key_size)?;
        let cl_value = match self.context.read_gs(&key)? {
            Some(stored_value) => CLValue::try_from(stored_value).map_err(Error::TypeMismatch)?,
            None => return Ok(Err(ApiError::ValueNotFound)),
        };

        let value_size = cl_value.inner_bytes().len() as u32;
        if let Err(error) = self.write_host_buf(cl_value) {
            return Ok(Err(error));
        }

        let value_bytes = value_size.to_le_bytes(); // Wasm is little-endian
        if let Err(error) = self.memory.set(output_size_ptr, &value_bytes) {
            return Err(Error::Interpreter(error).into());
        }

        Ok(Ok(()))
    }

    /// Similar to `read`, this function is for reading from the "local cluster"
    /// of global state
    fn read_local(
        &mut self,
        key_ptr: u32,
        key_size: u32,
        output_size_ptr: u32,
    ) -> Result<Result<(), ApiError>, Trap> {
        if !self.can_write_to_host_buf() {
            // Exit early if the host buffer is already occupied
            return Ok(Err(ApiError::HostBufferFull));
        }

        let key_bytes = self.bytes_from_mem(key_ptr, key_size as usize)?;

        let cl_value = match self.context.read_ls(&key_bytes)? {
            Some(cl_value) => cl_value,
            None => return Ok(Err(ApiError::ValueNotFound)),
        };

        let value_size = cl_value.inner_bytes().len() as u32;
        if let Err(error) = self.write_host_buf(cl_value) {
            return Ok(Err(error));
        }

        let value_bytes = value_size.to_le_bytes(); // Wasm is little-endian
        if let Err(error) = self.memory.set(output_size_ptr, &value_bytes) {
            return Err(Error::Interpreter(error).into());
        }

        Ok(Ok(()))
    }

    /// Reverts contract execution with a status specified.
    fn revert(&mut self, status: u32) -> Trap {
        Error::Revert(status).into()
    }

    fn add_associated_key(&mut self, public_key_ptr: u32, weight_value: u8) -> Result<i32, Trap> {
        let public_key = {
            // Public key as serialized bytes
            let source_serialized =
                self.bytes_from_mem(public_key_ptr, PUBLIC_KEY_SERIALIZED_LENGTH)?;
            // Public key deserialized
            let source: PublicKey =
                bytesrepr::deserialize(source_serialized).map_err(Error::BytesRepr)?;
            source
        };
        let weight = Weight::new(weight_value);

        match self.context.add_associated_key(public_key, weight) {
            Ok(_) => Ok(0),
            // This relies on the fact that `AddKeyFailure` is represented as
            // i32 and first variant start with number `1`, so all other variants
            // are greater than the first one, so it's safe to assume `0` is success,
            // and any error is greater than 0.
            Err(Error::AddKeyFailure(e)) => Ok(e as i32),
            // Any other variant just pass as `Trap`
            Err(e) => Err(e.into()),
        }
    }

    fn remove_associated_key(&mut self, public_key_ptr: u32) -> Result<i32, Trap> {
        let public_key = {
            // Public key as serialized bytes
            let source_serialized =
                self.bytes_from_mem(public_key_ptr, PUBLIC_KEY_SERIALIZED_LENGTH)?;
            // Public key deserialized
            let source: PublicKey =
                bytesrepr::deserialize(source_serialized).map_err(Error::BytesRepr)?;
            source
        };
        match self.context.remove_associated_key(public_key) {
            Ok(_) => Ok(0),
            Err(Error::RemoveKeyFailure(e)) => Ok(e as i32),
            Err(e) => Err(e.into()),
        }
    }

    fn update_associated_key(
        &mut self,
        public_key_ptr: u32,
        weight_value: u8,
    ) -> Result<i32, Trap> {
        let public_key = {
            // Public key as serialized bytes
            let source_serialized =
                self.bytes_from_mem(public_key_ptr, PUBLIC_KEY_SERIALIZED_LENGTH)?;
            // Public key deserialized
            let source: PublicKey =
                bytesrepr::deserialize(source_serialized).map_err(Error::BytesRepr)?;
            source
        };
        let weight = Weight::new(weight_value);

        match self.context.update_associated_key(public_key, weight) {
            Ok(_) => Ok(0),
            // This relies on the fact that `UpdateKeyFailure` is represented as
            // i32 and first variant start with number `1`, so all other variants
            // are greater than the first one, so it's safe to assume `0` is success,
            // and any error is greater than 0.
            Err(Error::UpdateKeyFailure(e)) => Ok(e as i32),
            // Any other variant just pass as `Trap`
            Err(e) => Err(e.into()),
        }
    }

    fn set_action_threshold(
        &mut self,
        action_type_value: u32,
        threshold_value: u8,
    ) -> Result<i32, Trap> {
        match ActionType::try_from(action_type_value) {
            Ok(action_type) => {
                let threshold = Weight::new(threshold_value);
                match self.context.set_action_threshold(action_type, threshold) {
                    Ok(_) => Ok(0),
                    Err(Error::SetThresholdFailure(e)) => Ok(e as i32),
                    Err(e) => Err(e.into()),
                }
            }
            Err(_) => Err(Trap::new(TrapKind::Unreachable)),
        }
    }

    /// Looks up the public mint contract key in the context's protocol data.
    ///
    /// Returned URef is already attenuated depending on the calling account.
    fn get_mint_contract_uref(&mut self) -> URef {
        let mint = self.context.protocol_data().mint();
        self.context.attenuate_uref(mint)
    }

    /// Looks up the public PoS contract key in the context's protocol data
    ///
    /// Returned URef is already attenuated depending on the calling account.
    fn get_pos_contract_uref(&mut self) -> URef {
        let pos = self.context.protocol_data().proof_of_stake();
        self.context.attenuate_uref(pos)
    }

    /// Calls the "create" method on the mint contract at the given mint
    /// contract key
    fn mint_create(&mut self, mint_contract_key: Key) -> Result<PurseId, Error> {
        let args_bytes = {
            let args = ("create",);
            ArgsParser::parse(args)?.into_bytes()?
        };

        let result = self.call_contract(mint_contract_key, args_bytes)?;
        let purse_uref = result.into_t()?;

        Ok(PurseId::new(purse_uref))
    }

    fn create_purse(&mut self) -> Result<PurseId, Error> {
        let mint_contract_key = self.get_mint_contract_uref().into();
        self.mint_create(mint_contract_key)
    }

    /// Calls the "transfer" method on the mint contract at the given mint
    /// contract key
    fn mint_transfer(
        &mut self,
        mint_contract_key: Key,
        source: PurseId,
        target: PurseId,
        amount: U512,
    ) -> Result<(), Error> {
        let source_value: URef = source.value();
        let target_value: URef = target.value();

        let args_bytes = {
            let args = ("transfer", source_value, target_value, amount);
            ArgsParser::parse(args)?.into_bytes()?
        };

        let result = self.call_contract(mint_contract_key, args_bytes)?;
        let result: Result<(), mint::Error> = result.into_t()?;
        Ok(result.map_err(system_contract_errors::Error::from)?)
    }

    /// Creates a new account at a given public key, transferring a given amount
    /// of motes from the given source purse to the new account's purse.
    fn transfer_to_new_account(
        &mut self,
        source: PurseId,
        target: PublicKey,
        amount: U512,
    ) -> Result<TransferResult, Error> {
        let mint_contract_key = self.get_mint_contract_uref().into();

        let target_addr = target.value();
        let target_key = Key::Account(target_addr);

        // A precondition check that verifies that the transfer can be done
        // as the source purse has enough funds to cover the transfer.
        if amount > self.get_balance(source)?.unwrap_or_default() {
            return Ok(Err(ApiError::Transfer));
        }

        let target_purse_id = self.mint_create(mint_contract_key)?;

        if source == target_purse_id {
            return Ok(Err(ApiError::Transfer));
        }

        match self.mint_transfer(mint_contract_key, source, target_purse_id, amount) {
            Ok(_) => {
                // After merging in EE-704 system contracts lookup internally uses protocol data and
                // this is used for backwards compatibility with explorer to query mint/pos urefs.
                let named_keys = vec![
                    (
                        String::from(MINT_NAME),
                        Key::from(self.get_mint_contract_uref()),
                    ),
                    (
                        String::from(POS_NAME),
                        Key::from(self.get_pos_contract_uref()),
                    ),
                ]
                .into_iter()
                .map(|(name, key)| {
                    if let Some(uref) = key.as_uref() {
                        (name, Key::URef(URef::new(uref.addr(), AccessRights::READ)))
                    } else {
                        (name, key)
                    }
                })
                .collect();
                let account = Account::create(target_addr, named_keys, target_purse_id);
                self.context.write_account(target_key, account)?;
                Ok(Ok(TransferredTo::NewAccount))
            }
            Err(_) => Ok(Err(ApiError::Transfer)),
        }
    }

    /// Transferring a given amount of motes from the given source purse to the
    /// new account's purse. Requires that the [`PurseId`]s have already
    /// been created by the mint contract (or are the genesis account's).
    fn transfer_to_existing_account(
        &mut self,
        source: PurseId,
        target: PurseId,
        amount: U512,
    ) -> Result<TransferResult, Error> {
        let mint_contract_key = self.get_mint_contract_uref().into();

        // This appears to be a load-bearing use of `RuntimeContext::insert_uref`.
        self.context.insert_uref(target.value());

        match self.mint_transfer(mint_contract_key, source, target, amount) {
            Ok(_) => Ok(Ok(TransferredTo::ExistingAccount)),
            Err(_) => Ok(Err(ApiError::Transfer)),
        }
    }

    /// Transfers `amount` of motes from default purse of the account to
    /// `target` account. If that account does not exist, creates one.
    fn transfer_to_account(
        &mut self,
        target: PublicKey,
        amount: U512,
    ) -> Result<TransferResult, Error> {
        let source = self.context.get_main_purse()?;
        self.transfer_from_purse_to_account(source, target, amount)
    }

    /// Transfers `amount` of motes from `source` purse to `target` account.
    /// If that account does not exist, creates one.
    fn transfer_from_purse_to_account(
        &mut self,
        source: PurseId,
        target: PublicKey,
        amount: U512,
    ) -> Result<TransferResult, Error> {
        let target_key = Key::Account(target.value());
        // Look up the account at the given public key's address
        match self.context.read_account(&target_key)? {
            None => {
                // If no account exists, create a new account and transfer the amount to its
                // purse.
                self.transfer_to_new_account(source, target, amount)
            }
            Some(StoredValue::Account(account)) => {
                let target = account.purse_id_add_only();
                if source == target {
                    return Ok(Ok(TransferredTo::ExistingAccount));
                }
                // If an account exists, transfer the amount to its purse
                self.transfer_to_existing_account(source, target, amount)
            }
            Some(_) => {
                // If some other value exists, return an error
                Err(Error::AccountNotFound(target_key))
            }
        }
    }

    /// Transfers `amount` of motes from `source` purse to `target` purse.
    fn transfer_from_purse_to_purse(
        &mut self,
        source_ptr: u32,
        source_size: u32,
        target_ptr: u32,
        target_size: u32,
        amount_ptr: u32,
        amount_size: u32,
    ) -> Result<Result<(), ApiError>, Error> {
        let source: PurseId = {
            let bytes = self.bytes_from_mem(source_ptr, source_size as usize)?;
            bytesrepr::deserialize(bytes).map_err(Error::BytesRepr)?
        };

        let target: PurseId = {
            let bytes = self.bytes_from_mem(target_ptr, target_size as usize)?;
            bytesrepr::deserialize(bytes).map_err(Error::BytesRepr)?
        };

        let amount: U512 = {
            let bytes = self.bytes_from_mem(amount_ptr, amount_size as usize)?;
            bytesrepr::deserialize(bytes).map_err(Error::BytesRepr)?
        };

        let mint_contract_key = self.get_mint_contract_uref().into();

        if self
            .mint_transfer(mint_contract_key, source, target, amount)
            .is_ok()
        {
            Ok(Ok(()))
        } else {
            Ok(Err(ApiError::Transfer))
        }
    }

    fn get_balance(&mut self, purse_id: PurseId) -> Result<Option<U512>, Error> {
        let seed = self.get_mint_contract_uref().addr();

        let key = purse_id.value().addr().into_bytes()?;

        let uref_key = match self.context.read_ls_with_seed(seed, &key)? {
            Some(cl_value) => {
                let key: Key = cl_value.into_t().expect("expected Key type");
                match key {
                    Key::URef(_) => (),
                    _ => panic!("expected Key::Uref(_)"),
                }
                key
            }
            None => return Ok(None),
        };

        let ret = match self.context.read_gs_direct(&uref_key)? {
            Some(StoredValue::CLValue(cl_value)) => {
                if *cl_value.cl_type() == CLType::U512 {
                    let balance: U512 = cl_value.into_t()?;
                    Some(balance)
                } else {
                    panic!("expected U512")
                }
            }
            Some(_) => panic!("expected U512"),
            None => None,
        };

        Ok(ret)
    }

    fn get_balance_host_buf(
        &mut self,
        purse_id_ptr: u32,
        purse_id_size: usize,
        output_size_ptr: u32,
    ) -> Result<Result<(), ApiError>, Error> {
        if !self.can_write_to_host_buf() {
            // Exit early if the host buffer is already occupied
            return Ok(Err(ApiError::HostBufferFull));
        }

        let purse_id: PurseId = {
            let bytes = self.bytes_from_mem(purse_id_ptr, purse_id_size)?;
            match bytesrepr::deserialize(bytes) {
                Ok(purse_id) => purse_id,
                Err(error) => return Ok(Err(error.into())),
            }
        };

        let balance = match self.get_balance(purse_id)? {
            Some(balance) => balance,
            None => return Ok(Err(ApiError::InvalidPurse)),
        };

        let balance_cl_value = match CLValue::from_t(balance) {
            Ok(cl_value) => cl_value,
            Err(error) => return Ok(Err(error.into())),
        };

        let balance_size = balance_cl_value.inner_bytes().len() as i32;
        if let Err(error) = self.write_host_buf(balance_cl_value) {
            return Ok(Err(error));
        }

        let balance_size_bytes = balance_size.to_le_bytes(); // Wasm is little-endian
        if let Err(error) = self.memory.set(output_size_ptr, &balance_size_bytes) {
            return Err(Error::Interpreter(error));
        }

        Ok(Ok(()))
    }

    /// If key is in named_keys with AccessRights::Write, processes bytes from calling contract
    /// and writes them at the provided uref, overwriting existing value if any
    fn upgrade_contract_at_uref(
        &mut self,
        name_ptr: u32,
        name_size: u32,
        key_ptr: u32,
        key_size: u32,
    ) -> Result<Result<(), ApiError>, Trap> {
        let key = self.key_from_mem(key_ptr, key_size)?;
        let named_keys = match self.context.read_gs(&key)? {
            None => Err(Error::KeyNotFound(key)),
            Some(StoredValue::Contract(contract)) => Ok(contract.named_keys().clone()),
            Some(_) => Err(Error::FunctionNotFound(format!(
                "Value at {:?} is not a contract",
                key
            ))),
        }?;
        let bytes = self.get_function_by_name(name_ptr, name_size)?;
        match self
            .context
            .upgrade_contract_at_uref(key, bytes, named_keys)
        {
            Ok(_) => Ok(Ok(())),
            Err(_) => Ok(Err(ApiError::UpgradeContractAtURef)),
        }
    }

    fn get_system_contract(
        &mut self,
        system_contract_index: u32,
        dest_ptr: u32,
        _dest_size: u32,
    ) -> Result<Result<(), ApiError>, Trap> {
        let attenuated_uref = match SystemContractType::try_from(system_contract_index) {
            Ok(SystemContractType::Mint) => self.get_mint_contract_uref(),
            Ok(SystemContractType::ProofOfStake) => self.get_pos_contract_uref(),
            Err(error) => return Ok(Err(error)),
        };

        // Serialize data that will be written the memory under `dest_ptr`
        let attenuated_uref_bytes = attenuated_uref.into_bytes().map_err(Error::BytesRepr)?;
        match self.memory.set(dest_ptr, &attenuated_uref_bytes) {
            Ok(_) => Ok(Ok(())),
            Err(error) => Err(Error::Interpreter(error).into()),
        }
    }

    /// If host_buf set, clears the host_buf and returns value, else None
    pub fn take_host_buf(&mut self) -> Option<CLValue> {
        self.host_buf.take()
    }

    /// Checks if a write to host buffer can happen.
    ///
    /// This will check if the host buffer is empty.
    fn can_write_to_host_buf(&self) -> bool {
        self.host_buf.is_none()
    }

    /// Overwrites data in host buffer only if it's in empty state
    fn write_host_buf(&mut self, data: CLValue) -> Result<(), ApiError> {
        match self.host_buf {
            Some(_) => return Err(ApiError::HostBufferFull),
            None => self.host_buf = Some(data),
        }
        Ok(())
    }

    fn read_host_buffer(
        &mut self,
        dest_ptr: u32,
        dest_size: usize,
        bytes_written_ptr: u32,
    ) -> Result<Result<(), ApiError>, Error> {
        let (_cl_type, serialized_value) = match self.take_host_buf() {
            None => return Ok(Err(ApiError::HostBufferEmpty)),
            Some(cl_value) => cl_value.destructure(),
        };

        if serialized_value.len() > u32::max_value() as usize {
            return Ok(Err(ApiError::OutOfMemoryError));
        }
        if serialized_value.len() > dest_size {
            return Ok(Err(ApiError::BufferTooSmall));
        }

        // Slice data, so if `dest_size` is larger than hostbuf size, it will take host_buf as
        // whole.
        let sliced_buf = &serialized_value[..cmp::min(dest_size, serialized_value.len())];
        if let Err(error) = self.memory.set(dest_ptr, sliced_buf) {
            return Err(Error::Interpreter(error));
        }

        let bytes_written = sliced_buf.len() as u32;
        let bytes_written_data = bytes_written.to_le_bytes();

        if let Err(error) = self.memory.set(bytes_written_ptr, &bytes_written_data) {
            return Err(Error::Interpreter(error));
        }

        Ok(Ok(()))
    }
}

#[cfg(test)]
mod tests {
    use proptest::{
        array,
        collection::{btree_map, vec},
        option,
        prelude::*,
        result,
    };

    use types::{gens::*, CLType, CLValue, Key, URef};

    use super::extract_urefs;

    fn cl_value_with_urefs_arb() -> impl Strategy<Value = (CLValue, Vec<URef>)> {
        // If compiler brings you here it most probably means you've added a variant to `CLType`
        // enum but forgot to add generator for it.
        let stub: Option<CLType> = None;
        if let Some(cl_type) = stub {
            match cl_type {
                CLType::Bool
                | CLType::I32
                | CLType::I64
                | CLType::U8
                | CLType::U32
                | CLType::U64
                | CLType::U128
                | CLType::U256
                | CLType::U512
                | CLType::Unit
                | CLType::String
                | CLType::Key
                | CLType::URef
                | CLType::Option(_)
                | CLType::List(_)
                | CLType::FixedList(..)
                | CLType::Result { .. }
                | CLType::Map { .. }
                | CLType::Tuple1(_)
                | CLType::Tuple2(_)
                | CLType::Tuple3(_)
                | CLType::Any => (),
            }
        };

        prop_oneof![
            Just((CLValue::from_t(()).expect("should create CLValue"), vec![])),
            any::<bool>()
                .prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            any::<i32>().prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            any::<i64>().prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            any::<u8>().prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            any::<u32>().prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            any::<u64>().prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            u128_arb().prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            u256_arb().prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            u512_arb().prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            key_arb().prop_map(|x| {
                let urefs = x.as_uref().into_iter().cloned().collect();
                (CLValue::from_t(x).expect("should create CLValue"), urefs)
            }),
            uref_arb().prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![x])),
            ".*".prop_map(|x: String| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            option::of(any::<u64>())
                .prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            option::of(uref_arb()).prop_map(|x| {
                let urefs = x.iter().cloned().collect();
                (CLValue::from_t(x).expect("should create CLValue"), urefs)
            }),
            option::of(key_arb()).prop_map(|x| {
                let urefs = x.iter().filter_map(Key::as_uref).cloned().collect();
                (CLValue::from_t(x).expect("should create CLValue"), urefs)
            }),
            vec(any::<i32>(), 0..100)
                .prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            vec(uref_arb(), 0..100).prop_map(|x| (
                CLValue::from_t(x.clone()).expect("should create CLValue"),
                x
            )),
            vec(key_arb(), 0..100).prop_map(|x| (
                CLValue::from_t(x.clone()).expect("should create CLValue"),
                x.into_iter().filter_map(Key::into_uref).collect()
            )),
            [any::<u64>(); 32]
                .prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            array::uniform8(uref_arb()).prop_map(|x| {
                let urefs = x.to_vec();
                (CLValue::from_t(x).expect("should create CLValue"), urefs)
            }),
            array::uniform8(key_arb()).prop_map(|x| {
                let urefs = x.iter().filter_map(Key::as_uref).cloned().collect();
                (CLValue::from_t(x).expect("should create CLValue"), urefs)
            }),
            result::maybe_err(key_arb(), ".*").prop_map(|x| {
                let urefs = match &x {
                    Ok(key) => key.as_uref().into_iter().cloned().collect(),
                    Err(_) => vec![],
                };
                (CLValue::from_t(x).expect("should create CLValue"), urefs)
            }),
            result::maybe_ok(".*", uref_arb()).prop_map(|x| {
                let urefs = match &x {
                    Ok(_) => vec![],
                    Err(uref) => vec![*uref],
                };
                (CLValue::from_t(x).expect("should create CLValue"), urefs)
            }),
            btree_map(".*", u512_arb(), 0..100)
                .prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            btree_map(uref_arb(), u512_arb(), 0..100).prop_map(|x| {
                let urefs = x.keys().cloned().collect();
                (CLValue::from_t(x).expect("should create CLValue"), urefs)
            }),
            btree_map(".*", uref_arb(), 0..100).prop_map(|x| {
                let urefs = x.values().cloned().collect();
                (CLValue::from_t(x).expect("should create CLValue"), urefs)
            }),
            btree_map(uref_arb(), key_arb(), 0..100).prop_map(|x| {
                let mut urefs: Vec<URef> = x.keys().cloned().collect();
                urefs.extend(x.values().filter_map(Key::as_uref).cloned());
                (CLValue::from_t(x).expect("should create CLValue"), urefs)
            }),
            (any::<bool>())
                .prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            (uref_arb())
                .prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![x])),
            (any::<bool>(), any::<i32>())
                .prop_map(|x| (CLValue::from_t(x).expect("should create CLValue"), vec![])),
            (uref_arb(), any::<i32>()).prop_map(|x| {
                let uref = x.0;
                (
                    CLValue::from_t(x).expect("should create CLValue"),
                    vec![uref],
                )
            }),
            (any::<i32>(), key_arb()).prop_map(|x| {
                let urefs = x.1.as_uref().into_iter().cloned().collect();
                (CLValue::from_t(x).expect("should create CLValue"), urefs)
            }),
            (uref_arb(), key_arb()).prop_map(|x| {
                let mut urefs = vec![x.0];
                urefs.extend(x.1.as_uref().into_iter().cloned());
                (CLValue::from_t(x).expect("should create CLValue"), urefs)
            }),
        ]
    }

    proptest! {
        #[test]
        fn should_extract_urefs((cl_value, urefs) in cl_value_with_urefs_arb()) {
            let extracted_urefs = extract_urefs(&cl_value).unwrap();
            assert_eq!(extracted_urefs, urefs);
        }
    }
}
