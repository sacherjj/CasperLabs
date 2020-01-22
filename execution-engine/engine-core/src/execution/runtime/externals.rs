use std::convert::TryFrom;

use wasmi::{Externals, RuntimeArgs, RuntimeValue, Trap};

use types::{
    account::PublicKey,
    api_error,
    bytesrepr::{self, ToBytes},
    Key, TransferredTo, U512,
};

use engine_shared::{gas::Gas, stored_value::StoredValue};
use engine_storage::global_state::StateReader;

use super::{args::Args, Error, Runtime};
use crate::resolvers::v1_function_index::FunctionIndex;

impl<'a, R> Externals for Runtime<'a, R>
where
    R: StateReader<Key, StoredValue>,
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
                // args(2) = pointer to output size (output param)
                let (key_ptr, key_size, output_size_ptr) = Args::parse(args)?;
                let ret = self.read(key_ptr, key_size, output_size_ptr)?;
                Ok(Some(RuntimeValue::I32(api_error::i32_from(ret))))
            }

            FunctionIndex::ReadLocalFuncIndex => {
                // args(0) = pointer to key in Wasm memory
                // args(1) = size of key in Wasm memory
                // args(2) = pointer to output size (output param)
                let (key_ptr, key_size, output_size_ptr) = Args::parse(args)?;
                let ret = self.read_local(key_ptr, key_size, output_size_ptr)?;
                Ok(Some(RuntimeValue::I32(api_error::i32_from(ret))))
            }

            FunctionIndex::LoadNamedKeysFuncIndex => {
                // args(0) = pointer to amount of keys (output)
                // args(1) = pointer to amount of serialized bytes (output)
                let (total_keys_ptr, result_size_ptr) = Args::parse(args)?;
                let ret = self.load_named_keys(total_keys_ptr, result_size_ptr)?;
                Ok(Some(RuntimeValue::I32(api_error::i32_from(ret))))
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

            FunctionIndex::AddLocalFuncIndex => {
                // args(0) = pointer to key in Wasm memory
                // args(1) = size of key
                // args(2) = pointer to value
                // args(3) = size of value
                let (key_bytes_ptr, key_bytes_size, value_ptr, value_size) = Args::parse(args)?;
                self.add_local(key_bytes_ptr, key_bytes_size, value_ptr, value_size)?;
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

            FunctionIndex::GetArgSizeFuncIndex => {
                // args(0) = index of host runtime arg to load
                // args(1) = pointer to a argument size (output)
                let (index, size_ptr): (u32, u32) = Args::parse(args)?;
                let ret = self.get_arg_size(index as usize, size_ptr)?;
                Ok(Some(RuntimeValue::I32(api_error::i32_from(ret))))
            }

            FunctionIndex::GetArgFuncIndex => {
                // args(0) = index of host runtime arg to load
                // args(1) = pointer to destination in Wasm memory
                // args(2) = size of destination pointer memory
                let (index, dest_ptr, dest_size): (u32, _, u32) = Args::parse(args)?;
                let ret = self.get_arg(index as usize, dest_ptr, dest_size as usize)?;
                Ok(Some(RuntimeValue::I32(api_error::i32_from(ret))))
            }

            FunctionIndex::RetFuncIndex => {
                // args(0) = pointer to value
                // args(1) = size of value
                let (value_ptr, value_size): (_, u32) = Args::parse(args)?;

                Err(self.ret(value_ptr, value_size as usize))
            }

            FunctionIndex::CallContractFuncIndex => {
                // args(0) = pointer to key where contract is at in global state
                // args(1) = size of key
                // args(2) = pointer to function arguments in Wasm memory
                // args(3) = size of arguments
                // args(4) = pointer to result size (output)
                let (key_ptr, key_size, args_ptr, args_size, result_size_ptr): (_, _, _, u32, _) =
                    Args::parse(args)?;

                let key_contract: Key = self.key_from_mem(key_ptr, key_size)?;
                let args_bytes: Vec<u8> = self.bytes_from_mem(args_ptr, args_size as usize)?;

                let ret = self.call_contract_host_buf(key_contract, args_bytes, result_size_ptr)?;
                Ok(Some(RuntimeValue::I32(api_error::i32_from(ret))))
            }

            FunctionIndex::GetKeyFuncIndex => {
                // args(0) = pointer to key name in Wasm memory
                // args(1) = size of key name
                // args(2) = pointer to output buffer for serialized key
                // args(3) = size of output buffer
                // args(4) = pointer to bytes written
                let (name_ptr, name_size, output_ptr, output_size, bytes_written): (
                    u32,
                    u32,
                    u32,
                    u32,
                    u32,
                ) = Args::parse(args)?;
                let ret = self.load_key(
                    name_ptr,
                    name_size,
                    output_ptr,
                    output_size as usize,
                    bytes_written,
                )?;
                Ok(Some(RuntimeValue::I32(api_error::i32_from(ret))))
            }

            FunctionIndex::HasKeyFuncIndex => {
                // args(0) = pointer to key name in Wasm memory
                // args(1) = size of key name
                let (name_ptr, name_size) = Args::parse(args)?;
                let result = self.has_key(name_ptr, name_size)?;
                Ok(Some(RuntimeValue::I32(result)))
            }

            FunctionIndex::PutKeyFuncIndex => {
                // args(0) = pointer to key name in Wasm memory
                // args(1) = size of key name
                // args(2) = pointer to key in Wasm memory
                // args(3) = size of key
                let (name_ptr, name_size, key_ptr, key_size) = Args::parse(args)?;
                self.put_key(name_ptr, name_size, key_ptr, key_size)?;
                Ok(None)
            }

            FunctionIndex::RemoveKeyFuncIndex => {
                // args(0) = pointer to key name in Wasm memory
                // args(1) = size of key name
                let (name_ptr, name_size) = Args::parse(args)?;
                self.remove_key(name_ptr, name_size)?;
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
                let gas_arg: u32 = Args::parse(args)?;
                self.gas(Gas::new(gas_arg.into()))?;
                Ok(None)
            }

            FunctionIndex::StoreFnIndex => {
                // args(0) = pointer to function name in Wasm memory
                // args(1) = size of the name
                // args(2) = pointer to additional unforgable names
                //           to be saved with the function body
                // args(3) = size of the additional unforgable names
                // args(4) = pointer to a Wasm memory where we will save
                //           uref address of the new function
                let (name_ptr, name_size, urefs_ptr, urefs_size, hash_ptr) = Args::parse(args)?;
                let _uref_type: u32 = urefs_size;
                let fn_bytes = self.get_function_by_name(name_ptr, name_size)?;
                let uref_bytes = self
                    .memory
                    .get(urefs_ptr, urefs_size as usize)
                    .map_err(Error::Interpreter)?;
                let urefs = bytesrepr::deserialize(uref_bytes).map_err(Error::BytesRepr)?;
                let contract_hash = self.store_function(fn_bytes, urefs)?;
                self.function_address(contract_hash, hash_ptr)?;
                Ok(None)
            }

            FunctionIndex::StoreFnAtHashIndex => {
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
                let urefs = bytesrepr::deserialize(uref_bytes).map_err(Error::BytesRepr)?;
                let contract_hash = self.store_function_at_hash(fn_bytes, urefs)?;
                self.function_address(contract_hash, hash_ptr)?;
                Ok(None)
            }

            FunctionIndex::IsValidURefFnIndex => {
                // args(0) = pointer to value to validate
                // args(1) = size of value
                let (uref_ptr, uref_size) = Args::parse(args)?;

                Ok(Some(RuntimeValue::I32(i32::from(
                    self.is_valid_uref(uref_ptr, uref_size)?,
                ))))
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
                let purse_id_bytes = purse_id.into_bytes().map_err(Error::BytesRepr)?;
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
                    bytesrepr::deserialize(bytes).map_err(Error::BytesRepr)?
                };
                let amount: U512 = {
                    let bytes = self.bytes_from_mem(amount_ptr, amount_size as usize)?;
                    bytesrepr::deserialize(bytes).map_err(Error::BytesRepr)?
                };
                let ret = self.transfer_to_account(public_key, amount)?;
                Ok(Some(RuntimeValue::I32(TransferredTo::i32_from(ret))))
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
                    bytesrepr::deserialize(bytes).map_err(Error::BytesRepr)?
                };
                let public_key: PublicKey = {
                    let bytes = self.bytes_from_mem(key_ptr, key_size as usize)?;
                    bytesrepr::deserialize(bytes).map_err(Error::BytesRepr)?
                };
                let amount: U512 = {
                    let bytes = self.bytes_from_mem(amount_ptr, amount_size as usize)?;
                    bytesrepr::deserialize(bytes).map_err(Error::BytesRepr)?
                };
                let ret = self.transfer_from_purse_to_account(source_purse, public_key, amount)?;
                Ok(Some(RuntimeValue::I32(TransferredTo::i32_from(ret))))
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
                Ok(Some(RuntimeValue::I32(api_error::i32_from(ret))))
            }

            FunctionIndex::GetBalanceIndex => {
                // args(0) = pointer to purse_id input
                // args(1) = length of purse_id
                // args(2) = pointer to output size (output)
                let (ptr, ptr_size, output_size_ptr): (_, u32, _) = Args::parse(args)?;
                let ret = self.get_balance_host_buf(ptr, ptr_size as usize, output_size_ptr)?;
                Ok(Some(RuntimeValue::I32(api_error::i32_from(ret))))
            }

            FunctionIndex::GetPhaseIndex => {
                // args(0) = pointer to Wasm memory where to write.
                let dest_ptr = Args::parse(args)?;
                self.get_phase(dest_ptr)?;
                Ok(None)
            }

            FunctionIndex::UpgradeContractAtURefIndex => {
                // args(0) = pointer to name in Wasm memory
                // args(1) = size of name in Wasm memory
                // args(2) = pointer to key in Wasm memory
                // args(3) = size of key
                let (name_ptr, name_size, key_ptr, key_size) = Args::parse(args)?;
                let ret = self.upgrade_contract_at_uref(name_ptr, name_size, key_ptr, key_size)?;
                Ok(Some(RuntimeValue::I32(api_error::i32_from(ret))))
            }

            FunctionIndex::GetSystemContractIndex => {
                // args(0) = system contract index
                // args(1) = dest pointer for storing serialized result
                // args(2) = dest pointer size
                let (system_contract_index, dest_ptr, dest_size) = Args::parse(args)?;
                let ret = self.get_system_contract(system_contract_index, dest_ptr, dest_size)?;
                Ok(Some(RuntimeValue::I32(api_error::i32_from(ret))))
            }

            FunctionIndex::GetMainPurseIndex => {
                // args(0) = pointer to Wasm memory where to write.
                let dest_ptr = Args::parse(args)?;
                self.get_main_purse(dest_ptr)?;
                Ok(None)
            }

            FunctionIndex::ReadHostBufferIndex => {
                // args(0) = pointer to Wasm memory where to write size.
                let (dest_ptr, dest_size, bytes_written_ptr): (_, u32, _) = Args::parse(args)?;
                let ret = self.read_host_buffer(dest_ptr, dest_size as usize, bytes_written_ptr)?;
                Ok(Some(RuntimeValue::I32(api_error::i32_from(ret))))
            }
        }
    }
}
