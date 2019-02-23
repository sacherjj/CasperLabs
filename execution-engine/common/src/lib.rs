#![no_std]
#![feature(alloc, allocator_api, core_intrinsics, lang_items, alloc_error_handler)]

extern crate alloc;
extern crate wee_alloc;

#[cfg(not(feature = "std"))]
#[global_allocator]
pub static ALLOC: wee_alloc::WeeAlloc = wee_alloc::WeeAlloc::INIT;

pub mod bytesrepr;
pub mod key;
pub mod value;

mod ext_ffi {
    extern "C" {
        pub fn read_value(key_ptr: *const u8, key_size: usize) -> usize;
        pub fn get_read(value_ptr: *mut u8); //can only be called after `read_value`
        pub fn write(key_ptr: *const u8, key_size: usize, value_ptr: *const u8, value_size: usize);
        pub fn add(key_ptr: *const u8, key_size: usize, value_ptr: *const u8, value_size: usize);
        pub fn new_uref(key_ptr: *mut u8);
        pub fn serialize_function(name_ptr: *const u8, name_size: usize) -> usize;
        pub fn get_function(dest_ptr: *mut u8); //can only be called after `serialize_function`
        pub fn function_address(dest_ptr: *mut u8);
        pub fn load_arg(i: u32) -> usize;
        pub fn get_arg(dest: *mut u8); //can only be called after `load_arg`
        pub fn ret(
            value_ptr: *const u8,
            value_size: usize,
            //extra urefs known by the current contract to make available to the caller
            extra_urefs_ptr: *const u8,
            extra_urefs_size: usize,
        ) -> !;
        pub fn call_contract(
            key_ptr: *const u8,
            key_size: usize,
            args_ptr: *const u8,
            args_size: usize,
            //extra urefs known by the caller to make available to the callee
            extra_urefs_ptr: *const u8,
            extra_urefs_size: usize,
        ) -> usize;
        pub fn get_call_result(res_ptr: *mut u8); //can only be called after `call_contract`
        pub fn get_uref(name_ptr: *const u8, name_size: usize, dest: *mut u8);
        pub fn has_uref_name(name_ptr: *const u8, name_size: usize) -> i32;
        pub fn add_uref(name_ptr: *const u8, name_size: usize, key_ptr: *const u8, key_size: usize);
    }
}

pub mod ext {
    use super::alloc::alloc::{Alloc, Global};
    use super::alloc::collections::BTreeMap;
    use super::alloc::string::String;
    use super::alloc::vec::Vec;
    use super::ext_ffi;
    use crate::bytesrepr::{deserialize, FromBytes, ToBytes};
    use crate::key::{Key, UREF_SIZE};
    use crate::value::Value;

    #[allow(clippy::zero_ptr)]
    fn alloc_bytes(n: usize) -> *mut u8 {
        if n == 0 {
            //cannot allocate with size 0
            0 as *mut u8
        } else {
            Global.alloc_array(n).unwrap().as_ptr()
        }
    }

    // I don't know why I need a special version of to_ptr for
    // &str, but the compiler complains if I try to use the polymorphic
    // version with T = str.
    fn str_ref_to_ptr(t: &str) -> (*const u8, usize, Vec<u8>) {
        let bytes = t.to_bytes();
        let ptr = bytes.as_ptr();
        let size = bytes.len();
        (ptr, size, bytes)
    }

    fn to_ptr<T: ToBytes>(t: &T) -> (*const u8, usize, Vec<u8>) {
        let bytes = t.to_bytes();
        let ptr = bytes.as_ptr();
        let size = bytes.len();
        (ptr, size, bytes)
    }

    //Read value under the key in the global state
    pub fn read(key: &Key) -> Value {
        //Note: _bytes is necessary to keep the Vec<u8> in scope. If _bytes is
        //      dropped then key_ptr becomes invalid.
        let (key_ptr, key_size, _bytes) = to_ptr(key);
        let value_size = unsafe { ext_ffi::read_value(key_ptr, key_size) };
        let value_ptr = alloc_bytes(value_size);
        let value_bytes = unsafe {
            ext_ffi::get_read(value_ptr);
            Vec::from_raw_parts(value_ptr, value_size, value_size)
        };
        deserialize(&value_bytes).unwrap()
    }

    //Write the value under the key in the global state
    pub fn write(key: &Key, value: &Value) {
        let (key_ptr, key_size, _bytes) = to_ptr(key);
        let (value_ptr, value_size, _bytes2) = to_ptr(value);
        unsafe {
            ext_ffi::write(key_ptr, key_size, value_ptr, value_size);
        }
    }

    //Add the given value to the one  currently under the key in the global state
    pub fn add(key: &Key, value: &Value) {
        let (key_ptr, key_size, _bytes) = to_ptr(key);
        let (value_ptr, value_size, _bytes2) = to_ptr(value);
        unsafe {
            //Could panic if the value under the key cannot be added to
            //the given value in memory
            ext_ffi::add(key_ptr, key_size, value_ptr, value_size);
        }
    }

    //Returns a new unforgable reference Key
    pub fn new_uref() -> Key {
        let key_ptr = alloc_bytes(UREF_SIZE);
        let bytes = unsafe {
            ext_ffi::new_uref(key_ptr);
            Vec::from_raw_parts(key_ptr, UREF_SIZE, UREF_SIZE)
        };
        deserialize(&bytes).unwrap()
    }

    fn fn_bytes_by_name(name: &str) -> Vec<u8> {
        let (name_ptr, name_size, _bytes) = str_ref_to_ptr(name);
        let fn_size = unsafe { ext_ffi::serialize_function(name_ptr, name_size) };
        let fn_ptr = alloc_bytes(fn_size);
        unsafe {
            ext_ffi::get_function(fn_ptr);
            Vec::from_raw_parts(fn_ptr, fn_size, fn_size)
        }
    }

    //Returns the serialized bytes of a function which is exported in the current module.
    //Note that the function is wrapped up in a new module and re-exported under the name
    //"call". `fn_bytes_by_name` is meant to be used when storing a contract on-chain at
    //an unforgable reference.
    pub fn fn_by_name(name: &str, known_urefs: BTreeMap<String, Key>) -> Value {
        let bytes = fn_bytes_by_name(name);
        Value::Contract { bytes, known_urefs }
    }

    //Gets the serialized bytes of an exported function (see `fn_by_name`), then
    //computes gets the address from the host to produce a key where the contract is then
    //stored in the global state. This key is returned.
    pub fn store_function(name: &str, known_urefs: BTreeMap<String, Key>) -> Key {
        let bytes = fn_bytes_by_name(name);
        let fn_hash = {
            let mut tmp = [0u8; 32];
            let addr_ptr = alloc_bytes(32);
            let bytes = unsafe {
                ext_ffi::function_address(addr_ptr);
                Vec::from_raw_parts(addr_ptr, 32, 32)
            };
            tmp.copy_from_slice(&bytes);
            tmp
        };
        let key = Key::Hash(fn_hash);
        let value = Value::Contract { bytes, known_urefs };
        write(&key, &value);
        key
    }

    //Return the i-th argument passed to the host for the current module
    //invokation. Note that this is only relevent to contracts stored on-chain
    //since a contract deployed directly is not invoked with any arguments.
    pub fn get_arg<T: FromBytes>(i: u32) -> T {
        let arg_size = unsafe { ext_ffi::load_arg(i) };
        let dest_ptr = alloc_bytes(arg_size);
        let arg_bytes = unsafe {
            ext_ffi::get_arg(dest_ptr);
            Vec::from_raw_parts(dest_ptr, arg_size, arg_size)
        };
        //TODO: better error handling (i.e. pass the `Result` on)
        deserialize(&arg_bytes).unwrap()
    }

    //Return the unforgable reference known by the current module under the given name.
    //This either comes from the known_urefs of the account or contract,
    //depending on whether the current module is a sub-call or not.
    pub fn get_uref(name: &str) -> Key {
        let (name_ptr, name_size, _bytes) = str_ref_to_ptr(name);
        let dest_ptr = alloc_bytes(UREF_SIZE);
        let uref_bytes = unsafe {
            ext_ffi::get_uref(name_ptr, name_size, dest_ptr);
            Vec::from_raw_parts(dest_ptr, UREF_SIZE, UREF_SIZE)
        };
        //TODO: better error handling (i.e. pass the `Result` on)
        deserialize(&uref_bytes).unwrap()
    }

    //Check if the given name corresponds to a known unforgable reference
    pub fn has_uref(name: &str) -> bool {
        let (name_ptr, name_size, _bytes) = str_ref_to_ptr(name);
        let result = unsafe { ext_ffi::has_uref_name(name_ptr, name_size) };
        result == 0
    }

    //Add the given key to the known_urefs map under the given name
    pub fn add_uref(name: &str, key: &Key) {
        let (name_ptr, name_size, _bytes) = str_ref_to_ptr(name);
        let (key_ptr, key_size, _bytes2) = to_ptr(key);
        unsafe { ext_ffi::add_uref(name_ptr, name_size, key_ptr, key_size) };
    }

    //Return `t` to the host, terminating the currently running module.
    //Note this function is only relevent to contracts stored on chain which
    //return a value to their caller. The return value of a directly deployed
    //contract is never looked at.
    #[allow(clippy::ptr_arg)]
    pub fn ret<T: ToBytes>(t: &T, extra_urefs: &Vec<Key>) -> ! {
        let (ptr, size, _bytes) = to_ptr(t);
        let (urefs_ptr, urefs_size, _bytes2) = to_ptr(extra_urefs);
        unsafe {
            ext_ffi::ret(ptr, size, urefs_ptr, urefs_size);
        }
    }

    //Call the given contract, passing the given (serialized) arguments to
    //the host in order to have them available to the called contract during its
    //execution. The value returned from the contract call (see `ret` above) is
    //returned from this function.
    #[allow(clippy::ptr_arg)]
    pub fn call_contract<T: FromBytes>(
        contract_key: &Key,
        args: &Vec<Vec<u8>>,
        extra_urefs: &Vec<Key>,
    ) -> T {
        let (key_ptr, key_size, _bytes1) = to_ptr(contract_key);
        let (args_ptr, args_size, _bytes2) = to_ptr(args);
        let (urefs_ptr, urefs_size, _bytes3) = to_ptr(extra_urefs);
        let res_size = unsafe {
            ext_ffi::call_contract(
                key_ptr, key_size, args_ptr, args_size, urefs_ptr, urefs_size,
            )
        };
        let res_ptr = alloc_bytes(res_size);
        let res_bytes = unsafe {
            ext_ffi::get_call_result(res_ptr);
            Vec::from_raw_parts(res_ptr, res_size, res_size)
        };
        deserialize(&res_bytes).unwrap()
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
