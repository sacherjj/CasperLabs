#![no_std]
#![feature(
    allocator_api,
    core_intrinsics,
    lang_items,
    alloc_error_handler,
    try_reserve
)]

#[macro_use]
extern crate alloc;

extern crate binascii;

#[macro_use]
extern crate uint;
#[macro_use]
extern crate failure;
extern crate wee_alloc;
#[macro_use]
extern crate bitflags;
#[macro_use]
extern crate num_derive;

#[cfg(any(test, feature = "gens"))]
extern crate proptest;

#[cfg(not(feature = "std"))]
#[global_allocator]
pub static ALLOC: wee_alloc::WeeAlloc = wee_alloc::WeeAlloc::INIT;

pub mod base16;
pub mod bytesrepr;
pub mod contract_api;
pub mod execution;
#[cfg(any(test, feature = "gens"))]
pub mod gens;
pub mod key;
pub mod system_contracts;
#[cfg(any(test, feature = "gens"))]
pub mod test_utils;
pub mod unwrap_or_revert;
pub mod uref;
pub mod value;

mod ext_ffi {
    extern "C" {
        pub fn read_value(key_ptr: *const u8, key_size: usize) -> usize;
        pub fn read_value_local(key_ptr: *const u8, key_size: usize) -> usize;
        pub fn get_read(value_ptr: *mut u8); //can only be called after `read_value` or `read_value_local`
        pub fn write(key_ptr: *const u8, key_size: usize, value_ptr: *const u8, value_size: usize);
        pub fn write_local(
            key_ptr: *const u8,
            key_size: usize,
            value_ptr: *const u8,
            value_size: usize,
        );
        pub fn add(key_ptr: *const u8, key_size: usize, value_ptr: *const u8, value_size: usize);
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
        pub fn serialize_named_keys() -> usize;
        // Can only be called after `serialize_named_keys`.
        pub fn list_named_keys(dest_ptr: *mut u8);
        pub fn load_arg(i: u32) -> isize;
        pub fn get_arg(dest: *mut u8); //can only be called after `load_arg`
        pub fn ret(
            value_ptr: *const u8,
            value_size: usize,
            // extra urefs known by the current contract to make available to the caller
            extra_urefs_ptr: *const u8,
            extra_urefs_size: usize,
        ) -> !;
        pub fn call_contract(
            key_ptr: *const u8,
            key_size: usize,
            args_ptr: *const u8,
            args_size: usize,
            // extra urefs known by the caller to make available to the callee
            extra_urefs_ptr: *const u8,
            extra_urefs_size: usize,
        ) -> usize;
        pub fn get_call_result(res_ptr: *mut u8); //can only be called after `call_contract`
        pub fn get_key(name_ptr: *const u8, name_size: usize) -> usize;
        pub fn has_key(name_ptr: *const u8, name_size: usize) -> i32;
        pub fn put_key(name_ptr: *const u8, name_size: usize, key_ptr: *const u8, key_size: usize);
        pub fn revert(status: u32) -> !;
        pub fn is_valid(value_ptr: *const u8, value_size: usize) -> i32;
        pub fn add_associated_key(public_key_ptr: *const u8, weight: i32) -> i32;
        pub fn remove_associated_key(public_key_ptr: *const u8) -> i32;
        pub fn update_associated_key(public_key_ptr: *const u8, weight: i32) -> i32;
        pub fn set_action_threshold(permission_level: u32, threshold: i32) -> i32;
        pub fn remove_key(name_ptr: *const u8, name_size: usize);
        pub fn get_caller(dest_ptr: *const u8);
        pub fn create_purse(purse_id_ptr: *const u8, purse_id_size: usize) -> i32;
        pub fn transfer_to_account(
            target_ptr: *const u8,
            target_size: usize,
            amount_ptr: *const u8,
            amount_size: usize,
        ) -> i32;
        pub fn get_blocktime(dest_ptr: *const u8);
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
        pub fn get_balance(purse_id_ptr: *const u8, purse_id_size: usize) -> i32;
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
    }
}

#[cfg(not(feature = "std"))]
pub mod handlers {

    #[panic_handler]
    #[no_mangle]
    pub fn panic(_info: &::core::panic::PanicInfo) -> ! {
        unsafe {
            ::core::intrinsics::abort();
        }
    }

    #[alloc_error_handler]
    #[no_mangle]
    pub extern "C" fn oom(_: ::core::alloc::Layout) -> ! {
        unsafe {
            ::core::intrinsics::abort();
        }
    }

    #[lang = "eh_personality"]
    extern "C" fn eh_personality() {}
}
