#![no_std]
#![no_main]

extern crate alloc;

use alloc::{string::String, vec};

use alloc::string::ToString;
use types::{CLValue, ContractHash, NamedArg, RuntimeArgs, URef, U512};

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};

const ARG_AMOUNT: &str = "amount";
const ARG_PURSE: &str = "purse";
const ARG_SOURCE: &str = "source";
const ARG_TARGET: &str = "target";
const ARG_CREATE: &str = "create";
const ARG_TRANSFER: &str = "transfer";
const ARG_BALANCE: &str = "balance";

#[no_mangle]
pub extern "C" fn call() {
    let mint: ContractHash = system::get_mint();

    let source = get_purse(mint.clone(), 100);
    let target = get_purse(mint.clone(), 300);

    assert!(
        transfer(mint.clone(), source, target, U512::from(70)) == "Success!",
        "transfer should succeed"
    );

    assert!(
        balance(mint.clone(), source).unwrap() == U512::from(30),
        "source purse balance incorrect"
    );
    assert!(
        balance(mint.clone(), target).unwrap() == U512::from(370),
        "target balance incorrect"
    );
}

fn get_purse(mint: ContractHash, amount: u64) -> URef {
    let amount = U512::from(amount);
    let runtime_args = {
        let args = vec![NamedArg::new(
            ARG_AMOUNT.to_string(),
            CLValue::from_t(amount).unwrap_or_revert(),
        )];
        RuntimeArgs::Named(args)
    };

    runtime::call_contract::<URef>(mint.clone(), ARG_CREATE, runtime_args)
}

fn transfer(mint: ContractHash, source: URef, target: URef, amount: U512) -> String {
    let runtime_args = {
        let args = vec![
            NamedArg::new(
                ARG_AMOUNT.to_string(),
                CLValue::from_t(amount).unwrap_or_revert(),
            ),
            NamedArg::new(
                ARG_SOURCE.to_string(),
                CLValue::from_t(source).unwrap_or_revert(),
            ),
            NamedArg::new(
                ARG_TARGET.to_string(),
                CLValue::from_t(target).unwrap_or_revert(),
            ),
        ];
        RuntimeArgs::Named(args)
    };

    runtime::call_contract::<String>(mint, ARG_TRANSFER, runtime_args)
}

fn balance(mint: ContractHash, purse: URef) -> Option<U512> {
    let runtime_args = {
        let args = vec![NamedArg::new(
            ARG_PURSE.to_string(),
            CLValue::from_t(purse).unwrap_or_revert(),
        )];
        RuntimeArgs::Named(args)
    };
    runtime::call_contract::<Option<U512>>(mint.clone(), ARG_BALANCE, runtime_args)
}
