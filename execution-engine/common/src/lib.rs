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
        pub fn load_arg(i: u32) -> usize;
        pub fn get_arg(dest: *mut u8); //can only be called after `load_arg`
        pub fn ret(value_ptr: *const u8, value_size: usize) -> !;
        pub fn call_contract(
            fn_ptr: *const u8,
            fn_size: usize,
            args_ptr: *const u8,
            args_size: usize,
        ) -> usize;
        pub fn get_call_result(res_ptr: *mut u8); //can only be called after `call_contract`
    }
}

pub mod ext {
    extern crate blake2;

    use super::alloc::alloc::{Alloc, Global};
    use super::alloc::string::String;
    use super::alloc::vec::Vec;
    use super::ext_ffi;
    use blake2::digest::{Input, VariableOutput};
    use blake2::VarBlake2b;
    use crate::bytesrepr::{deserialize, BytesRepr};
    use crate::key::{Key, UREF_SIZE};
    use crate::value::Value;

    fn alloc_bytes(n: usize) -> *mut u8 {
        Global.alloc_array(n).unwrap().as_ptr()
    }

    fn to_ptr<T: BytesRepr>(t: &T) -> (*const u8, usize, Vec<u8>) {
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
            core::slice::from_raw_parts(value_ptr, value_size)
        };
        deserialize(value_bytes).unwrap()
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
            ext_ffi::add(key_ptr, key_size, value_ptr, value_size);
        }
    }

    //Returns a new unforgable reference Key
    pub fn new_uref() -> Key {
        let key_ptr = alloc_bytes(UREF_SIZE);
        let slice = unsafe {
            ext_ffi::new_uref(key_ptr);
            core::slice::from_raw_parts(key_ptr, UREF_SIZE)
        };
        deserialize(slice).unwrap()
    }

    fn fn_bytes_by_name(name: &String) -> Vec<u8> {
        let (name_ptr, name_size, _bytes) = to_ptr(name);
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
    pub fn fn_by_name(name: &String) -> Value {
        let fn_bytes = fn_bytes_by_name(name);
        Value::Contract(fn_bytes)
    }

    //Gets the serialized bytes of an exported function (see `fn_by_name`), then
    //computes the hash of those bytes to produce a key where the contract is then
    //stored in the global state. This key is returned.
    pub fn store_function(name: &String) -> Key {
        let fn_bytes = fn_bytes_by_name(name);
        let mut hasher = VarBlake2b::new(32).unwrap();
        hasher.input(&fn_bytes);
        let mut fn_hash = [0u8; 32];
        hasher.variable_result(|hash| fn_hash.clone_from_slice(hash));
        let key = Key::Hash(fn_hash);
        let value = Value::Contract(fn_bytes);
        write(&key, &value);
        key
    }

    //Return the i-th argument passed to the host for the current module
    //invokation. Note that this is only relevent to contracts stored on-chain
    //since a contract deployed directly is not invoked with any arguments.
    pub fn get_arg<T: BytesRepr>(i: u32) -> T {
        let arg_size = unsafe { ext_ffi::load_arg(i) };
        let dest_ptr = alloc_bytes(arg_size);
        let arg_bytes = unsafe {
            ext_ffi::get_arg(dest_ptr);
            core::slice::from_raw_parts(dest_ptr, arg_size)
        };
        //TODO: better error handling (i.e. pass the `Result` on)
        deserialize(arg_bytes).unwrap()
    }

    //Return `t` to the host, terminating the currently running module.
    //Note this function is only relevent to contracts stored on chain which
    //return a value to their caller. The return value of a directly deployed
    //contract is never looked at.
    pub fn ret<T: BytesRepr>(t: &T) -> ! {
        let (ptr, size, _bytes) = to_ptr(t);
        unsafe {
            ext_ffi::ret(ptr, size);
        }
    }

    //Call the given contract, passing the given (serialized) arguments to
    //the host in order to have them available to the called contract during its
    //execution. The value returned from the contract call (see `ret` above) is
    //returned from this function.
    pub fn call_contract<T: BytesRepr>(contract: &Value, args: &Vec<Vec<u8>>) -> T {
        if let Value::Contract(c) = contract {
            let (fn_ptr, fn_size, _bytes) = to_ptr(c);
            let (args_ptr, args_size, _bytes2) = to_ptr(args);
            let res_size = unsafe { ext_ffi::call_contract(fn_ptr, fn_size, args_ptr, args_size) };
            let res_ptr = alloc_bytes(res_size);
            let res_bytes = unsafe {
                ext_ffi::get_call_result(res_ptr);
                core::slice::from_raw_parts(res_ptr, res_size)
            };
            deserialize(res_bytes).unwrap()
        } else {
            panic!("{:?} is not a contract!", contract);
        }
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
