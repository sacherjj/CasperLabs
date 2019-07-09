#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::uref::{AccessRights, URef};
use cl_std::value::account::PurseId;

// This value was acquired by observing the output of an execution of this contract
// made by ACCOUNT_1.
const EXPECTED_UREF_BYTES: [u8; 32] = [
    0x5f, 0x36, 0x40, 0xb1, 0xa3, 0xd6, 0x5d, 0xe6, 0x4e, 0x5b, 0x62, 0xf2, 0x44, 0x08, 0xbf, 0x67,
    0xf4, 0x67, 0xda, 0x2a, 0xc4, 0x2b, 0x5a, 0xb9, 0x52, 0xb7, 0xe5, 0x02, 0xd1, 0x52, 0xc1, 0xad,
];

#[no_mangle]
pub extern "C" fn call() {
    let expected_purse_id =
        PurseId::new(URef::new(EXPECTED_UREF_BYTES, AccessRights::READ_ADD_WRITE));

    let actual_purse_id = cl_std::contract_api::create_purse();

    assert_eq!(actual_purse_id, expected_purse_id);
}
