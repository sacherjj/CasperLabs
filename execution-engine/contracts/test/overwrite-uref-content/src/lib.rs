#![no_std]

use contract::{
    contract_api::{runtime, storage, Error as ApiError, TURef},
    unwrap_or_revert::UnwrapOrRevert,
    uref::{AccessRights, URef},
};

const CONTRACT_UREF: u32 = 0;

#[repr(u16)]
enum Error {
    CreateTURef,
}

const REPLACEMENT_DATA: &str = "bawitdaba";

#[no_mangle]
pub extern "C" fn call() {
    let arg: URef = runtime::get_arg(CONTRACT_UREF)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let reference = URef::new(arg.addr(), AccessRights::READ_ADD_WRITE);

    let forged_reference: TURef<&str> = {
        let ret = URef::new(reference.addr(), AccessRights::READ_ADD_WRITE);
        TURef::from_uref(ret)
            .unwrap_or_else(|_| runtime::revert(ApiError::User(Error::CreateTURef as u16)))
    };

    storage::write(forged_reference, &REPLACEMENT_DATA)
}
