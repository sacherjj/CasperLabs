#![no_std]

use contract::{
    contract_api::{account, runtime, Error},
    unwrap_or_revert::UnwrapOrRevert,
    value::account::PurseId,
};

#[no_mangle]
pub extern "C" fn call() {
    let known_main_purse: PurseId = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let main_purse: PurseId = account::get_main_purse();
    assert_eq!(
        main_purse, known_main_purse,
        "main purse was not known purse"
    );
}
