use std::convert::TryFrom;

use wasmi::{Externals, RuntimeArgs, RuntimeValue, Trap};

use contract_ffi::key::Key;
use contract_ffi::value::{Value, U512};
use engine_storage::global_state::StateReader;

use super::args::Args;
use super::{Error, Runtime};
use crate::resolvers::v1_function_index::FunctionIndex;
use contract_ffi::bytesrepr::{self, ToBytes};
use contract_ffi::value::account::{PublicKey, PurseId};

impl<'a, R: StateReader<Key, Value>> Externals for Runtime<'a, R>
where
    R::Error: Into<Error>,
{
    fn invoke_index(
        &mut self,
        index: usize,
        args: RuntimeArgs,
    ) -> Result<Option<RuntimeValue>, Trap> {
        let func = FunctionIndex::try_from(index).expect("unknown function index");
        match func {
            FunctionIndex::ReadFuncIndex => {
                // args(0) = pointer to key in Wasm memory
                // args(1) = size of key in Wasm memory
                let (key_ptr, key_size) = Args::parse(args)?;
                let size = self.read(key_ptr, key_size)?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            FunctionIndex::ReadLocalFuncIndex => {
                // args(0) = pointer to key bytes in Wasm memory
                // args(1) = size of key bytes in Wasm memory
                let (key_bytes_ptr, key_bytes_size) = Args::parse(args)?;
                let size = self.read_local(key_bytes_ptr, key_bytes_size)?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            FunctionIndex::SerFnFuncIndex => {
                // args(0) = pointer to name in Wasm memory
                // args(1) = size of name in Wasm memory
                let (name_ptr, name_size) = Args::parse(args)?;
                let size = self.serialize_function(name_ptr, name_size)?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            FunctionIndex::SerKnownURefs => {
                // No args, returns byte size of the known URefs.
                let size = self.serialize_known_urefs()?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            FunctionIndex::WriteFuncIndex => {
                // args(0) = pointer to key in Wasm memory
                // args(1) = size of key
                // args(2) = pointer to value
                // args(3) = size of value
                let (key_ptr, key_size, value_ptr, value_size) = Args::parse(args)?;
                self.write(key_ptr, key_size, value_ptr, value_size)?;
                Ok(None)
            }

            FunctionIndex::WriteLocalFuncIndex => {
                // args(0) = pointer to key in Wasm memory
                // args(1) = size of key
                // args(2) = pointer to value
                // args(3) = size of value
                let (key_bytes_ptr, key_bytes_size, value_ptr, value_size) = Args::parse(args)?;
                self.write_local(key_bytes_ptr, key_bytes_size, value_ptr, value_size)?;
                Ok(None)
            }

            FunctionIndex::AddFuncIndex => {
                // args(0) = pointer to key in Wasm memory
                // args(1) = size of key
                // args(2) = pointer to value
                // args(3) = size of value
                let (key_ptr, key_size, value_ptr, value_size) = Args::parse(args)?;
                self.add(key_ptr, key_size, value_ptr, value_size)?;
                Ok(None)
            }

            FunctionIndex::NewFuncIndex => {
                // args(0) = pointer to key destination in Wasm memory
                // args(1) = pointer to initial value
                // args(2) = size of initial value
                let (key_ptr, value_ptr, value_size) = Args::parse(args)?;
                self.new_uref(key_ptr, value_ptr, value_size)?;
                Ok(None)
            }

            FunctionIndex::GetReadFuncIndex => {
                // args(0) = pointer to destination in Wasm memory
                let dest_ptr = Args::parse(args)?;
                self.set_mem_from_buf(dest_ptr)?;
                Ok(None)
            }

            FunctionIndex::GetFnFuncIndex => {
                // args(0) = pointer to destination in Wasm memory
                let dest_ptr = Args::parse(args)?;
                self.set_mem_from_buf(dest_ptr)?;
                Ok(None)
            }

            FunctionIndex::LoadArgFuncIndex => {
                // args(0) = index of host runtime arg to load
                let i = Args::parse(args)?;
                let size = self.load_arg(i)?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            FunctionIndex::GetArgFuncIndex => {
                // args(0) = pointer to destination in Wasm memory
                let dest_ptr = Args::parse(args)?;
                self.set_mem_from_buf(dest_ptr)?;
                Ok(None)
            }

            FunctionIndex::RetFuncIndex => {
                // args(0) = pointer to value
                // args(1) = size of value
                // args(2) = pointer to extra returned urefs
                // args(3) = size of extra urefs
                let (value_ptr, value_size, extra_urefs_ptr, extra_urefs_size): (_, u32, _, u32) =
                    Args::parse(args)?;

                Err(self.ret(
                    value_ptr,
                    value_size as usize,
                    extra_urefs_ptr,
                    extra_urefs_size as usize,
                ))
            }

            FunctionIndex::CallContractFuncIndex => {
                // args(0) = pointer to key where contract is at in global state
                // args(1) = size of key
                // args(2) = pointer to function arguments in Wasm memory
                // args(3) = size of arguments
                // args(4) = pointer to extra supplied urefs
                // args(5) = size of extra urefs
                let (key_ptr, key_size, args_ptr, args_size, extra_urefs_ptr, extra_urefs_size) =
                    Args::parse(args)?;

                // We have to explicitly tell rustc what type we expect as it cannot infer it
                // otherwise.
                let _args_size_u32: u32 = args_size;
                let _extra_urefs_size_u32: u32 = extra_urefs_size;

                let key_contract: Key = self.key_from_mem(key_ptr, key_size)?;
                let args_bytes: Vec<u8> = self.bytes_from_mem(args_ptr, args_size as usize)?;
                let urefs_bytes =
                    self.bytes_from_mem(extra_urefs_ptr, extra_urefs_size as usize)?;

                let size = self.call_contract(key_contract, args_bytes, urefs_bytes)?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            FunctionIndex::GetCallResultFuncIndex => {
                // args(0) = pointer to destination in Wasm memory
                let dest_ptr = Args::parse(args)?;
                self.set_mem_from_buf(dest_ptr)?;
                Ok(None)
            }

            FunctionIndex::GetURefFuncIndex => {
                // args(0) = pointer to uref name in Wasm memory
                // args(1) = size of uref name
                let (name_ptr, name_size) = Args::parse(args)?;
                let size = self.get_uref(name_ptr, name_size)?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            FunctionIndex::HasURefFuncIndex => {
                // args(0) = pointer to uref name in Wasm memory
                // args(1) = size of uref name
                let (name_ptr, name_size) = Args::parse(args)?;
                let result = self.has_uref(name_ptr, name_size)?;
                Ok(Some(RuntimeValue::I32(result)))
            }

            FunctionIndex::AddURefFuncIndex => {
                // args(0) = pointer to uref name in Wasm memory
                // args(1) = size of uref name
                // args(2) = pointer to destination in Wasm memory
                let (name_ptr, name_size, key_ptr, key_size) = Args::parse(args)?;
                self.add_uref(name_ptr, name_size, key_ptr, key_size)?;
                Ok(None)
            }

            FunctionIndex::ListKnownURefsIndex => {
                // args(0) = pointer to destination in Wasm memory
                let ptr = Args::parse(args)?;
                self.list_known_urefs(ptr)?;
                Ok(None)
            }

            FunctionIndex::RemoveURef => {
                // args(0) = pointer to uref name in Wasm memory
                // args(1) = size of uref name
                let (name_ptr, name_size) = Args::parse(args)?;
                self.remove_uref(name_ptr, name_size)?;
                Ok(None)
            }

            FunctionIndex::GetCallerIndex => {
                // args(0) = pointer to Wasm memory where to write.
                let dest_ptr = Args::parse(args)?;
                self.get_caller(dest_ptr)?;
                Ok(None)
            }

            FunctionIndex::GetBlocktimeIndex => {
                // args(0) = pointer to Wasm memory where to write.
                let dest_ptr = Args::parse(args)?;
                self.get_blocktime(dest_ptr)?;
                Ok(None)
            }

            FunctionIndex::GasFuncIndex => {
                let gas: u32 = Args::parse(args)?;
                self.gas(u64::from(gas))?;
                Ok(None)
            }

            FunctionIndex::StoreFnIndex => {
                // args(0) = pointer to function name in Wasm memory
                // args(1) = size of the name
                // args(2) = pointer to additional unforgable names
                //           to be saved with the function body
                // args(3) = size of the additional unforgable names
                // args(4) = pointer to a Wasm memory where we will save
                //           hash of the new function
                let (name_ptr, name_size, urefs_ptr, urefs_size, hash_ptr) = Args::parse(args)?;
                let _uref_type: u32 = urefs_size;
                let fn_bytes = self.get_function_by_name(name_ptr, name_size)?;
                let uref_bytes = self
                    .memory
                    .get(urefs_ptr, urefs_size as usize)
                    .map_err(Error::Interpreter)?;
                let urefs = bytesrepr::deserialize(&uref_bytes).map_err(Error::BytesRepr)?;
                let contract_hash = self.store_function(fn_bytes, urefs)?;
                self.function_address(contract_hash, hash_ptr)?;
                Ok(None)
            }

            FunctionIndex::ProtocolVersionFuncIndex => {
                Ok(Some(self.context.protocol_version().into()))
            }

            FunctionIndex::IsValidFnIndex => {
                // args(0) = pointer to value to validate
                // args(1) = size of value
                let (value_ptr, value_size) = Args::parse(args)?;

                if self.value_is_valid(value_ptr, value_size)? {
                    Ok(Some(RuntimeValue::I32(1)))
                } else {
                    Ok(Some(RuntimeValue::I32(0)))
                }
            }

            FunctionIndex::RevertFuncIndex => {
                // args(0) = status u32
                let status = Args::parse(args)?;

                Err(self.revert(status))
            }

            FunctionIndex::AddAssociatedKeyFuncIndex => {
                // args(0) = pointer to array of bytes of a public key
                // args(1) = weight of the key
                let (public_key_ptr, weight_value): (u32, u8) = Args::parse(args)?;
                let value = self.add_associated_key(public_key_ptr, weight_value)?;
                Ok(Some(RuntimeValue::I32(value)))
            }

            FunctionIndex::RemoveAssociatedKeyFuncIndex => {
                // args(0) = pointer to array of bytes of a public key
                // args(1) = size of serialized bytes of public key
                let public_key_ptr: u32 = Args::parse(args)?;
                let value = self.remove_associated_key(public_key_ptr)?;
                Ok(Some(RuntimeValue::I32(value)))
            }

            FunctionIndex::UpdateAssociatedKeyFuncIndex => {
                // args(0) = pointer to array of bytes of a public key
                // args(1) = weight of the key
                let (public_key_ptr, weight_value): (u32, u8) = Args::parse(args)?;
                let value = self.update_associated_key(public_key_ptr, weight_value)?;
                Ok(Some(RuntimeValue::I32(value)))
            }

            FunctionIndex::SetActionThresholdFuncIndex => {
                // args(0) = action type
                // args(1) = new threshold
                let (action_type_value, threshold_value): (u32, u8) = Args::parse(args)?;
                let value = self.set_action_threshold(action_type_value, threshold_value)?;
                Ok(Some(RuntimeValue::I32(value)))
            }

            FunctionIndex::CreatePurseIndex => {
                // args(0) = pointer to array for return value
                // args(1) = length of array for return value
                let (dest_ptr, dest_size): (u32, u32) = Args::parse(args)?;
                let purse_id = self.create_purse()?;
                let purse_id_bytes = purse_id.to_bytes().map_err(Error::BytesRepr)?;
                assert_eq!(dest_size, purse_id_bytes.len() as u32);
                self.memory
                    .set(dest_ptr, &purse_id_bytes)
                    .map_err(Error::Interpreter)?;
                Ok(Some(RuntimeValue::I32(0)))
            }

            FunctionIndex::TransferToAccountIndex => {
                // args(0) = pointer to array of bytes of a public key
                // args(1) = length of array of bytes of a public key
                // args(2) = pointer to array of bytes of an amount
                // args(3) = length of array of bytes of an amount
                let (key_ptr, key_size, amount_ptr, amount_size): (u32, u32, u32, u32) =
                    Args::parse(args)?;
                let public_key: PublicKey = {
                    let bytes = self.bytes_from_mem(key_ptr, key_size as usize)?;
                    bytesrepr::deserialize(&bytes).map_err(Error::BytesRepr)?
                };
                let amount: U512 = {
                    let bytes = self.bytes_from_mem(amount_ptr, amount_size as usize)?;
                    bytesrepr::deserialize(&bytes).map_err(Error::BytesRepr)?
                };
                let ret = self.transfer_to_account(public_key, amount)?;
                Ok(Some(RuntimeValue::I32(ret.into())))
            }

            FunctionIndex::TransferFromPurseToAccountIndex => {
                // args(0) = pointer to array of bytes in Wasm memory of a source purse
                // args(1) = length of array of bytes in Wasm memory of a source purse
                // args(2) = pointer to array of bytes in Wasm memory of a public key
                // args(3) = length of array of bytes in Wasm memory of a public key
                // args(4) = pointer to array of bytes in Wasm memory of an amount
                // args(5) = length of array of bytes in Wasm memory of an amount
                let (source_ptr, source_size, key_ptr, key_size, amount_ptr, amount_size): (
                    u32,
                    u32,
                    u32,
                    u32,
                    u32,
                    u32,
                ) = Args::parse(args)?;

                let source_purse = {
                    let bytes = self.bytes_from_mem(source_ptr, source_size as usize)?;
                    bytesrepr::deserialize(&bytes).map_err(Error::BytesRepr)?
                };
                let public_key: PublicKey = {
                    let bytes = self.bytes_from_mem(key_ptr, key_size as usize)?;
                    bytesrepr::deserialize(&bytes).map_err(Error::BytesRepr)?
                };
                let amount: U512 = {
                    let bytes = self.bytes_from_mem(amount_ptr, amount_size as usize)?;
                    bytesrepr::deserialize(&bytes).map_err(Error::BytesRepr)?
                };
                let ret = self.transfer_from_purse_to_account(source_purse, public_key, amount)?;
                Ok(Some(RuntimeValue::I32(ret.into())))
            }

            FunctionIndex::TransferFromPurseToPurseIndex => {
                // args(0) = pointer to array of bytes in Wasm memory of a source purse
                // args(1) = length of array of bytes in Wasm memory of a source purse
                // args(2) = pointer to array of bytes in Wasm memory of a target purse
                // args(3) = length of array of bytes in Wasm memory of a target purse
                // args(4) = pointer to array of bytes in Wasm memory of an amount
                // args(5) = length of array of bytes in Wasm memory of an amount
                let (source_ptr, source_size, target_ptr, target_size, amount_ptr, amount_size) =
                    Args::parse(args)?;
                let ret = self.transfer_from_purse_to_purse(
                    source_ptr,
                    source_size,
                    target_ptr,
                    target_size,
                    amount_ptr,
                    amount_size,
                )?;
                Ok(Some(RuntimeValue::I32(ret.into())))
            }

            FunctionIndex::GetBalanceIndex => {
                // args(0) = pointer to purse_id input
                // args(1) = length of purse_id
                let (ptr, ptr_size): (u32, u32) = Args::parse(args)?;

                let purse_id: PurseId = {
                    let bytes = self.bytes_from_mem(ptr, ptr_size as usize)?;
                    bytesrepr::deserialize(&bytes).map_err(Error::BytesRepr)?
                };

                let ret = match self.get_balance(purse_id)? {
                    Some(balance) => {
                        let balance_bytes = balance.to_bytes().map_err(Error::BytesRepr)?;
                        self.host_buf = balance_bytes;
                        self.host_buf.len() as i32
                    }
                    None => 0i32,
                };

                Ok(Some(RuntimeValue::I32(ret)))
            }

            FunctionIndex::GetPhaseIndex => {
                // args(0) = pointer to Wasm memory where to write.
                let dest_ptr = Args::parse(args)?;
                self.get_phase(dest_ptr)?;
                Ok(None)
            }
        }
    }
}
