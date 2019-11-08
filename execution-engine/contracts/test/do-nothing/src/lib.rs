#![no_std]

// Required to bring `#[panic_handler]` from `contract_ffi::handlers` into scope.
#[allow(unused_imports)]
use contract_ffi;

#[no_mangle]
pub extern "C" fn call() {
    // This body intentionally left empty.
}
