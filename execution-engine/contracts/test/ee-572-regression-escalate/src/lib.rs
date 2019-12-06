#![no_std]

extern crate alloc;

use alloc::vec::Vec;

use contract_ffi::{
    contract_api::{runtime, storage, Error as ApiError, TURef},
    key::Key,
    unwrap_or_revert::UnwrapOrRevert,
    uref::{AccessRights, URef},
};

const CONTRACT_POINTER: u32 = 0;

#[repr(u16)]
enum Error {
    GetArgument = 0,
}

const REPLACEMENT_DATA: &str = "bawitdaba";

#[no_mangle]
pub extern "C" fn call() {
    let arg: Key = runtime::get_arg(CONTRACT_POINTER)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let contract_pointer = arg
        .to_c_ptr()
        .unwrap_or_revert_with(ApiError::User(Error::GetArgument as u16));

    let reference: URef = runtime::call_contract(contract_pointer, &(), &Vec::new())
        .into_t()
        .unwrap_or_revert();

    let forged_reference: TURef<&str> = {
        let ret = URef::new(reference.addr(), AccessRights::READ_ADD_WRITE);
        TURef::from_uref(ret).unwrap_or_revert()
    };

    storage::write(forged_reference, &REPLACEMENT_DATA)
}
