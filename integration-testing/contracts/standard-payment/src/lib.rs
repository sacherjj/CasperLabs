#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::pointers::ContractPointer;
use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

const GET_PAYMENT_PURSE: &str = "get_payment_purse";

enum Arg {
    Amount = 0,
}

#[allow(clippy::enum_variant_names)]
enum Error {
    GetPosURef = 1,
    TransferError = 2,
}

fn get_pos_contract() -> ContractPointer {
    contract_api::get_pos().unwrap_or_else(|| contract_api::revert(Error::GetPosURef as u32))
}

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = contract_api::get_arg(Arg::Amount as u32);
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
