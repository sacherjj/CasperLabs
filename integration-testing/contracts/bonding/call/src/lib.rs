#![no_std]

#[macro_use]
extern crate alloc;
//extern crate contract_ffi;

extern crate contract_ffi;
use contract_ffi::contract_api;
use contract_ffi::contract_api::pointers::UPointer;
use contract_ffi::key::Key;
use contract_ffi::value::uint::U512;
use contract_ffi::contract_api::{call_contract, PurseTransferResult, get_uref, transfer_from_purse_to_purse, revert};

#[no_mangle]
pub extern "C" fn call() {
    let pos_public: UPointer<Key> = get_uref("pos").unwrap().to_u_ptr().unwrap();
    let pos_contract: Key = contract_api::read(pos_public);
    let pos_pointer = pos_contract.to_c_ptr().unwrap();

    let source_purse = contract_api::main_purse();
    let bonding_purse = contract_api::create_purse();
    let bond_amount:U512 = U512::from(contract_api::get_arg::<u32>(0));

    match transfer_from_purse_to_purse(source_purse, bonding_purse, bond_amount) {
        PurseTransferResult::TransferSuccessful => {
            let _result: () = call_contract(
                pos_pointer,
                &("bond", bond_amount, bonding_purse),
                &vec![Key::URef(bonding_purse.value())],
            );
        }

        PurseTransferResult::TransferError => revert(1324),
    }
}
