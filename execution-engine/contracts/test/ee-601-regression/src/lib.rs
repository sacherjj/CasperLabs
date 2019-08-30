#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::string::{String, ToString};
use contract_ffi::contract_api::pointers::{ContractPointer, UPointer};
use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::key::Key;
use contract_ffi::uref::AccessRights;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

const POS_CONTRACT_NAME: &str = "pos";
const GET_PAYMENT_PURSE: &str = "get_payment_purse";
const NEW_UREF_RESULT_UREF_NAME: &str = "new_uref_result";

enum Arg {
    Amount = 0,
}

enum Error {
    GetPosInnerURef = 1,
    GetPosOuterURef = 2,
    Transfer = 3,
    InvalidPhase = 999,
}

#[no_mangle]
pub extern "C" fn call() {
    let phase = contract_api::get_phase();
    if phase == contract_ffi::execution::Phase::Payment {
        let amount: U512 = contract_api::get_arg(Arg::Amount as u32);
        let main_purse: PurseId = contract_api::main_purse();

        let pos_pointer: ContractPointer = {
            let outer: UPointer<Key> = contract_api::get_uref(POS_CONTRACT_NAME)
                .and_then(Key::to_u_ptr)
                .unwrap_or_else(|| contract_api::revert(Error::GetPosInnerURef as u32));
            if let Some(ContractPointer::URef(inner)) = contract_api::read::<Key>(outer).to_c_ptr()
            {
                ContractPointer::URef(UPointer::new(inner.0, AccessRights::READ))
            } else {
                contract_api::revert(Error::GetPosOuterURef as u32);
            }
        };

        let payment_purse: PurseId =
            contract_api::call_contract(pos_pointer, &(GET_PAYMENT_PURSE,), &vec![]);

        if let PurseTransferResult::TransferError =
            contract_api::transfer_from_purse_to_purse(main_purse, payment_purse, amount)
        {
            contract_api::revert(Error::Transfer as u32);
        }
    }

    let value: Option<&str> = {
        match phase {
            contract_ffi::execution::Phase::Payment => Some("payment"),
            contract_ffi::execution::Phase::Session => Some("session"),
            _ => None,
        }
    };
    let value = value.unwrap_or_else(|| contract_api::revert(Error::InvalidPhase as u32));
    let result_key = contract_api::new_uref(value.to_string()).into();
    let mut uref_name: String = NEW_UREF_RESULT_UREF_NAME.to_string();
    uref_name.push_str("-");
    uref_name.push_str(value);
    contract_api::add_uref(&uref_name, &result_key);
}
