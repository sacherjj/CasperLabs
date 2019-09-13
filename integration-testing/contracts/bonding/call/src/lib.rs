#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::pointers::{ContractPointer, TURef};
use contract_ffi::contract_api::{
    call_contract, create_purse, get_arg, get_uref, main_purse, read, revert,
    transfer_from_purse_to_purse, PurseTransferResult,
};
use contract_ffi::key::Key;
use contract_ffi::uref::AccessRights;
use contract_ffi::value::uint::U512;

enum Error {
    GetPosOuterURef = 1000,
    GetPosInnerURef = 1001,
}

fn get_pos_contract() -> ContractPointer {
    let outer: TURef<Key> = get_uref("pos")
        .and_then(Key::to_turef)
        .unwrap_or_else(|| revert(Error::GetPosInnerURef as u32));
    if let Some(ContractPointer::URef(inner)) = read::<Key>(outer).to_c_ptr() {
        ContractPointer::URef(TURef::new(inner.get_addr(), AccessRights::READ))
    } else {
        revert(Error::GetPosOuterURef as u32)
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_contract = get_pos_contract();
    let source_purse = main_purse();
    let bonding_purse = create_purse();
    let bond_amount: U512 = U512::from(get_arg::<u32>(0));

    match transfer_from_purse_to_purse(source_purse, bonding_purse, bond_amount) {
        PurseTransferResult::TransferSuccessful => {
            let _result: () = call_contract(
                pos_contract,
                &("bond", bond_amount, bonding_purse),
                &vec![Key::URef(bonding_purse.value())],
            );
        }

        PurseTransferResult::TransferError => revert(1324),
    }
}
