#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::pointers::{ContractPointer, TURef};
use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::key::Key;
use contract_ffi::uref::AccessRights;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

const POS_CONTRACT_NAME: &str = "pos";
const GET_PAYMENT_PURSE: &str = "get_payment_purse";

enum Arg {
    Amount = 0,
}

#[allow(clippy::enum_variant_names)]
enum Error {
    GetPosInnerURef = 1,
    GetPosOuterURef = 2,
    TransferError = 3,
}

fn get_pos_contract() -> ContractPointer {
    let outer: TURef<Key> = contract_api::get_uref(POS_CONTRACT_NAME)
        .and_then(Key::to_turef)
        .unwrap_or_else(|| contract_api::revert(Error::GetPosInnerURef as u32));
    if let Some(ContractPointer::URef(inner)) = contract_api::read::<Key>(outer).to_c_ptr() {
        ContractPointer::URef(TURef::new(inner.addr(), AccessRights::READ))
    } else {
        contract_api::revert(Error::GetPosOuterURef as u32);
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = contract_api::get_arg(Arg::Amount as u32).unwrap().unwrap();
    let main_purse: PurseId = contract_api::main_purse();

    let pos_pointer: ContractPointer = get_pos_contract();

    let payment_purse: PurseId =
        contract_api::call_contract(pos_pointer, &(GET_PAYMENT_PURSE,), &vec![]);

    if let PurseTransferResult::TransferError =
        contract_api::transfer_from_purse_to_purse(main_purse, payment_purse, amount)
    {
        contract_api::revert(Error::TransferError as u32);
    }
}
