#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::uref::{AccessRights, URef};
use cl_std::value::account::PurseId;

const EXPECTED_UREF_BYTES: [u8; 32] = [
    73, 143, 110, 138, 106, 168, 247, 100, 112, 181, 14, 171, 133, 47, 108, 16, 3, 147, 232, 172,
    251, 67, 247, 26, 160, 197, 79, 100, 233, 232, 174, 118,
];

#[no_mangle]
pub extern "C" fn call() {
    let expected_purse_id =
        PurseId::new(URef::new(EXPECTED_UREF_BYTES, AccessRights::READ_ADD_WRITE));

    let actual_purse_id = cl_std::contract_api::create_purse();

    assert_eq!(actual_purse_id, expected_purse_id);
}
