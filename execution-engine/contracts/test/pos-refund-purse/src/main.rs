#![no_std]
#![no_main]

use contract::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, ContractRef, URef, U512};

#[repr(u16)]
enum Error {
    ShouldNotExist = 0,
    NotFound,
    Invalid,
    IncorrectAccessRights,
}

fn set_refund_purse(pos: &ContractRef, p: &URef) {
    runtime::call_contract(pos.clone(), ("set_refund_purse", *p))
}

fn get_refund_purse(pos: &ContractRef) -> Option<URef> {
    runtime::call_contract(pos.clone(), ("get_refund_purse",))
}

fn get_payment_purse(pos: &ContractRef) -> URef {
    runtime::call_contract(pos.clone(), ("get_payment_purse",))
}

fn submit_payment(pos: &ContractRef, amount: U512) {
    let payment_purse = get_payment_purse(pos);
    let main_purse = account::get_main_purse();
    system::transfer_from_purse_to_purse(main_purse, payment_purse, amount).unwrap_or_revert()
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = system::get_proof_of_stake();

    let refund_purse = system::create_purse();
    {
        // get_refund_purse should return None before setting it
        let refund_result = get_refund_purse(&pos_pointer);
        if refund_result.is_some() {
            runtime::revert(ApiError::User(Error::ShouldNotExist as u16));
        }

        // it should return Some(x) after calling set_refund_purse(x)
        set_refund_purse(&pos_pointer, &refund_purse);
        let refund_purse = match get_refund_purse(&pos_pointer) {
            None => runtime::revert(ApiError::User(Error::NotFound as u16)),
            Some(x) if x.addr() == refund_purse.addr() => x,
            Some(_) => runtime::revert(ApiError::User(Error::Invalid as u16)),
        };

        // the returned purse should not have any access rights
        if refund_purse.is_addable() || refund_purse.is_writeable() || refund_purse.is_readable() {
            runtime::revert(ApiError::User(Error::IncorrectAccessRights as u16))
        }
    }
    {
        let refund_purse = system::create_purse();
        // get_refund_purse should return correct value after setting a second time
        set_refund_purse(&pos_pointer, &refund_purse);
        match get_refund_purse(&pos_pointer) {
            None => runtime::revert(ApiError::User(Error::NotFound as u16)),
            Some(uref) if uref.addr() == refund_purse.addr() => (),
            Some(_) => runtime::revert(ApiError::User(Error::Invalid as u16)),
        }

        let payment_amount: U512 = runtime::get_arg(0)
            .unwrap_or_revert_with(ApiError::MissingArgument)
            .unwrap_or_revert_with(ApiError::InvalidArgument);

        submit_payment(&pos_pointer, payment_amount);
    }
}
