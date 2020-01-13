#![no_std]

use contract::{
    contract_api::{account, runtime, Error},
    unwrap_or_revert::UnwrapOrRevert,
    value::account::PublicKey,
};

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    account::remove_associated_key(account).unwrap_or_revert_with(Error::User(0))
}
