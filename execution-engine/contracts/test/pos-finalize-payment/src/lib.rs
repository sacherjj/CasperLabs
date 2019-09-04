#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::vec::Vec;

use contract_ffi::contract_api::pointers::{ContractPointer, UPointer};
use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::key::Key;
use contract_ffi::uref::AccessRights;
use contract_ffi::value::account::{PublicKey, PurseId};
use contract_ffi::value::U512;

enum Error {
    GetPosOuterURef = 1,
    GetPosInnerURef = 2,
    SubmitPayment = 99,
}

fn purse_to_key(p: &PurseId) -> Key {
    Key::URef(p.value())
}

fn set_refund_purse(pos: &ContractPointer, p: &PurseId) {
    contract_api::call_contract::<_, ()>(
        pos.clone(),
        &("set_refund_purse", *p),
        &vec![purse_to_key(p)],
    );
}

fn get_payment_purse(pos: &ContractPointer) -> PurseId {
    contract_api::call_contract(pos.clone(), &("get_payment_purse",), &Vec::new())
}

fn submit_payment(pos: &ContractPointer, amount: U512) {
    let payment_purse = get_payment_purse(pos);
    let main_purse = contract_api::main_purse();
    if let PurseTransferResult::TransferError =
        contract_api::transfer_from_purse_to_purse(main_purse, payment_purse, amount)
    {
        contract_api::revert(Error::SubmitPayment as u32);
    }
}

fn finalize_payment(pos: &ContractPointer, amount_spent: U512, account: PublicKey) {
    contract_api::call_contract::<_, ()>(
        pos.clone(),
        &("finalize_payment", amount_spent, account),
        &Vec::new(),
    )
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = {
        let outer: UPointer<Key> = contract_api::get_uref("pos")
            .and_then(Key::to_u_ptr)
            .unwrap_or_else(|| contract_api::revert(Error::GetPosInnerURef as u32));
        if let Some(ContractPointer::URef(inner)) = contract_api::read::<Key>(outer).to_c_ptr() {
            ContractPointer::URef(UPointer::new(inner.0, AccessRights::READ))
        } else {
            contract_api::revert(Error::GetPosOuterURef as u32);
        }
    };

    let payment_amount: U512 = contract_api::get_arg(0);
    let refund_purse_flag: u8 = contract_api::get_arg(1);
    let maybe_amount_spent: Option<U512> = contract_api::get_arg(2);
    let maybe_account: Option<PublicKey> = contract_api::get_arg(3);

    submit_payment(&pos_pointer, payment_amount);
    if refund_purse_flag != 0 {
        let refund_purse = contract_api::create_purse();
        contract_api::add_uref("local_refund_purse", &Key::URef(refund_purse.value()));
        set_refund_purse(&pos_pointer, &refund_purse);
    }

    if let (Some(amount_spent), Some(account)) = (maybe_amount_spent, maybe_account) {
        finalize_payment(&pos_pointer, amount_spent, account);
    }
}
