//! Contains low-level bindings for host-side ("external") functions.
//!
//! Generally should not be used directly.  See the [`contract_api`](crate::contract_api) for
//! high-level bindings suitable for writing smart contracts.
extern "C" {
    pub fn read_value(key_ptr: *const u8, key_size: usize, output_size: *mut usize) -> i32;
    pub fn read_value_local(key_ptr: *const u8, key_size: usize, output_size: *mut usize) -> i32;
    pub fn write(key_ptr: *const u8, key_size: usize, value_ptr: *const u8, value_size: usize);
    pub fn write_local(
        key_ptr: *const u8,
        key_size: usize,
        value_ptr: *const u8,
        value_size: usize,
    );
    pub fn add(key_ptr: *const u8, key_size: usize, value_ptr: *const u8, value_size: usize);
    pub fn add_local(key_ptr: *const u8, key_size: usize, value_ptr: *const u8, value_size: usize);
    pub fn new_uref(key_ptr: *mut u8, value_ptr: *const u8, value_size: usize);
    pub fn store_function(
        function_name_ptr: *const u8,
        function_name_size: usize,
        named_keys_ptr: *const u8,
        named_keys_size: usize,
        uref_addr_ptr: *const u8,
    );
    pub fn store_function_at_hash(
        function_name_ptr: *const u8,
        function_name_size: usize,
        named_keys_ptr: *const u8,
        named_keys_size: usize,
        hash_ptr: *const u8,
    );
    pub fn load_named_keys(total_keys: *mut usize, result_size: *mut usize) -> i32;
    pub fn get_arg(index: usize, dest_ptr: *mut u8, dest_size: usize) -> i32;
    pub fn get_arg_size(index: usize, dest_size: *mut usize) -> i32;
    pub fn ret(value_ptr: *const u8, value_size: usize) -> !;
    pub fn call_contract(
        key_ptr: *const u8,
        key_size: usize,
        args_ptr: *const u8,
        args_size: usize,
        result_size: *mut usize,
    ) -> i32;
    pub fn get_key(
        name_ptr: *const u8,
        name_size: usize,
        output_ptr: *mut u8,
        output_size: usize,
        bytes_written_ptr: *mut usize,
    ) -> i32;
    pub fn has_key(name_ptr: *const u8, name_size: usize) -> i32;
    pub fn put_key(name_ptr: *const u8, name_size: usize, key_ptr: *const u8, key_size: usize);
    pub fn remove_key(name_ptr: *const u8, name_size: usize);
    pub fn revert(status: u32) -> !;
    pub fn is_valid_uref(uref_ptr: *const u8, uref_size: usize) -> i32;
    pub fn add_associated_key(public_key_ptr: *const u8, weight: i32) -> i32;
    pub fn remove_associated_key(public_key_ptr: *const u8) -> i32;
    pub fn update_associated_key(public_key_ptr: *const u8, weight: i32) -> i32;
    pub fn set_action_threshold(permission_level: u32, threshold: i32) -> i32;
    pub fn get_caller(dest_ptr: *const u8);
    pub fn get_blocktime(dest_ptr: *const u8);
    pub fn create_purse(purse_id_ptr: *const u8, purse_id_size: usize) -> i32;
    pub fn transfer_to_account(
        target_ptr: *const u8,
        target_size: usize,
        amount_ptr: *const u8,
        amount_size: usize,
    ) -> i32;
    pub fn transfer_from_purse_to_account(
        source_ptr: *const u8,
        source_size: usize,
        target_ptr: *const u8,
        target_size: usize,
        amount_ptr: *const u8,
        amount_size: usize,
    ) -> i32;
    pub fn transfer_from_purse_to_purse(
        source_ptr: *const u8,
        source_size: usize,
        target_ptr: *const u8,
        target_size: usize,
        amount_ptr: *const u8,
        amount_size: usize,
    ) -> i32;
    pub fn get_balance(
        purse_id_ptr: *const u8,
        purse_id_size: usize,
        result_size: *mut usize,
    ) -> i32;
    pub fn get_phase(dest_ptr: *mut u8);
    pub fn upgrade_contract_at_uref(
        name_ptr: *const u8,
        name_size: usize,
        key_ptr: *const u8,
        key_size: usize,
    ) -> i32;
    pub fn get_system_contract(
        system_contract_index: u32,
        dest_ptr: *mut u8,
        dest_size: usize,
    ) -> i32;
    pub fn get_main_purse(dest_ptr: *mut u8);
    pub fn read_host_buffer(dest_ptr: *mut u8, dest_size: usize, bytes_written: *mut usize) -> i32;
}
