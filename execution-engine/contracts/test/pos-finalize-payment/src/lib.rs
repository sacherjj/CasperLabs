#![no_std]
#![feature(alloc)]

#[macro_use]
extern crate alloc;
extern crate cl_std;

use alloc::vec::Vec;

use cl_std::contract_api::pointers::{ContractPointer, UPointer};
use cl_std::contract_api::{self, PurseTransferResult};
use cl_std::key::Key;
use cl_std::value::account::{PublicKey, PurseId};
use cl_std::value::U512;

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
    contract_api::call_contract(pos.clone(), &"get_payment_purse", &Vec::new())
}

fn submit_payment(pos: &ContractPointer, amount: U512) {
    let payment_purse = get_payment_purse(pos);
    let main_purse = contract_api::main_purse();
    if let PurseTransferResult::TransferError =
        contract_api::transfer_from_purse_to_purse(main_purse, payment_purse, amount)
    {
        contract_api::revert(99);
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
    let pos_public: UPointer<Key> = contract_api::get_uref("pos").unwrap().to_u_ptr().unwrap();
    let pos_contract: Key = contract_api::read(pos_public);
    let pos_pointer = pos_contract.to_c_ptr().unwrap();

    let payment_amount: U512 = contract_api::get_arg(0);
    let refund_purse: Option<PurseId> = contract_api::get_arg(1);
    let amount_spent: U512 = contract_api::get_arg(2);
    let account: PublicKey = contract_api::get_arg(3);

    submit_payment(&pos_pointer, payment_amount);
    if let Some(purse) = refund_purse {
        set_refund_purse(&pos_pointer, &purse);
    }
    finalize_payment(&pos_pointer, amount_spent, account);
}
