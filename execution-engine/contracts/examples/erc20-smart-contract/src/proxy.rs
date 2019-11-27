use alloc::vec;

use contract_ffi::{contract_api::runtime, value::U512};

use crate::{
    api::{self, Api},
    error::Error,
};

#[no_mangle]
pub extern "C" fn erc20_proxy() {
    let token_ref = Api::destination_contract();
    match Api::from_args_in_proxy() {
        Api::Transfer(recipient, amount) => {
            runtime::call_contract::<_, ()>(
                token_ref.clone(),
                &(api::TRANSFER, recipient, amount),
                &vec![],
            );
        }
        Api::TransferFrom(owner, recipient, amount) => {
            runtime::call_contract::<_, ()>(
                token_ref.clone(),
                &(api::TRANSFER_FROM, owner, recipient, amount),
                &vec![],
            );
        }
        Api::Approve(spender, amount) => {
            runtime::call_contract::<_, ()>(
                token_ref.clone(),
                &(api::APPROVE, spender, amount),
                &vec![],
            );
        }
        Api::AssertBalance(address, expected_amount) => {
            let balance = runtime::call_contract::<_, U512>(
                token_ref.clone(),
                &(api::BALANCE_OF, address),
                &vec![],
            );
            if balance != expected_amount {
                runtime::revert(Error::BalanceAssertionFailure)
            }
        }
        Api::AssertTotalSupply(expected_total_supply) => {
            let total_supply = runtime::call_contract::<_, U512>(
                token_ref.clone(),
                &(api::TOTAL_SUPPLY,),
                &vec![],
            );
            if total_supply != expected_total_supply {
                runtime::revert(Error::TotalSupplyAssertionFailure)
            }
        }
        Api::AssertAllowance(owner, spender, expected_amount) => {
            let allowance = runtime::call_contract::<_, U512>(
                token_ref.clone(),
                &(api::ALLOWANCE, owner, spender),
                &vec![],
            );
            if allowance != expected_amount {
                runtime::revert(Error::AllowanceAssertionFailure)
            }
        }
        _ => runtime::revert(Error::UnknownProxyCommand),
    }
}
