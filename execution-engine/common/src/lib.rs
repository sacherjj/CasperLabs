#![no_std]
#![feature(
    alloc,
    allocator_api,
    core_intrinsics,
    lang_items,
    alloc_error_handler,
    maybe_uninit
)]

#[macro_use]
extern crate alloc;

#[macro_use]
extern crate uint;
extern crate failure;
extern crate wee_alloc;
#[macro_use]
extern crate bitflags;

#[cfg(any(test, feature = "gens"))]
extern crate proptest;

extern crate heapsize;

#[cfg(not(feature = "std"))]
#[global_allocator]
pub static ALLOC: wee_alloc::WeeAlloc = wee_alloc::WeeAlloc::INIT;

pub mod bytesrepr;
pub mod contract_api;
#[cfg(any(test, feature = "gens"))]
pub mod gens;
pub mod key;
#[cfg(any(test, feature = "gens"))]
pub mod test_utils;
pub mod value;

mod ext_ffi {
    extern "C" {
        pub fn read_value(key_ptr: *const u8, key_size: usize) -> usize;
        pub fn get_read(value_ptr: *mut u8); //can only be called after `read_value`
        pub fn write(key_ptr: *const u8, key_size: usize, value_ptr: *const u8, value_size: usize);
        pub fn add(key_ptr: *const u8, key_size: usize, value_ptr: *const u8, value_size: usize);
        //TODO: update the FFI to take the initial value to place in the GS under the new URef
        pub fn new_uref(key_ptr: *mut u8, value_ptr: *const u8, value_size: usize);
        pub fn serialize_function(name_ptr: *const u8, name_size: usize) -> usize;
        pub fn get_function(dest_ptr: *mut u8); //can only be called after `serialize_function`
        pub fn store_function(
            value_ptr: *const u8,
            value_size: usize,
            extra_urefs_ptr: *const u8,
            extra_urefs_size: usize,
            hash_ptr: *const u8,
        );
        pub fn load_arg(i: u32) -> usize;
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
        pub fn get_uref(name_ptr: *const u8, name_size: usize, dest: *mut u8);
        pub fn has_uref_name(name_ptr: *const u8, name_size: usize) -> i32;
        pub fn add_uref(name_ptr: *const u8, name_size: usize, key_ptr: *const u8, key_size: usize);
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
