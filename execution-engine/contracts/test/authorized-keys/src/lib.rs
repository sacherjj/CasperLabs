#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

#[no_mangle]
pub extern "C" fn call() {
    // NOTE: To verify that the authorized deploys are working, no special
    // logic is necessary in the context.
    // Once contract API gain support for listing authorized keys we could
    // add them here.
}
