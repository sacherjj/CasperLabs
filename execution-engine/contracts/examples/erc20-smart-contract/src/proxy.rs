use alloc::vec;

use crate::{
    api::{self, Api},
    error::Error,
};
use contract_ffi::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
    value::U512,
};

#[no_mangle]
pub extern "C" fn erc20_proxy() {
    let token_ref = Api::destination_contract();
    match Api::from_args_in_proxy() {
        Api::Transfer(recipient, amount) => {
            runtime::call_contract::<_, ()>(
                token_ref.clone(),
                (api::TRANSFER, recipient, amount),
                vec![],
            );
        }
        Api::TransferFrom(owner, recipient, amount) => {
            runtime::call_contract::<_, ()>(
                token_ref.clone(),
                (api::TRANSFER_FROM, owner, recipient, amount),
                vec![],
            );
        }
        Api::Approve(spender, amount) => {
            runtime::call_contract::<_, ()>(
                token_ref.clone(),
                (api::APPROVE, spender, amount),
                vec![],
            );
        }
        Api::AssertBalance(address, expected_amount) => {
            let balance = runtime::call_contract::<_, U512>(
                token_ref.clone(),
                (api::BALANCE_OF, address),
                vec![],
            );
            if expected_amount != balance {
                runtime::revert(Error::BalanceAssertionFailure)
            }
        }
        Api::AssertTotalSupply(expected_total_supply) => {
            let total_supply =
                runtime::call_contract::<_, U512>(token_ref.clone(), (api::TOTAL_SUPPLY,), vec![]);
            if expected_total_supply != total_supply {
                runtime::revert(Error::TotalSupplyAssertionFailure)
            }
        }
        Api::AssertAllowance(owner, spender, expected_amount) => {
            let allowance = runtime::call_contract::<_, U512>(
                token_ref.clone(),
                (api::ALLOWANCE, owner, spender),
                vec![],
            );
            if expected_amount != allowance {
                runtime::revert(Error::AllowanceAssertionFailure)
            }
        }
        Api::BuyProxy(clx_amount) => {
            let main_purse = account::get_main_purse();
            let new_purse = system::create_purse();
            system::transfer_from_purse_to_purse(main_purse, new_purse, clx_amount)
                .unwrap_or_revert_with(Error::PurseTransferError);
            runtime::call_contract::<_, ()>(
                token_ref.clone(),
                &(api::BUY, new_purse),
                &vec![new_purse.value().into()],
            );
        }
        Api::SellProxy(tokens_amount) => {
            let new_purse = system::create_purse();
            runtime::call_contract::<_, ()>(
                token_ref.clone(),
                &(api::SELL, new_purse, tokens_amount),
                &vec![new_purse.value().into()],
            );
            let main_purse = account::get_main_purse();
            system::transfer_from_purse_to_purse(new_purse, main_purse, tokens_amount)
                .unwrap_or_revert_with(Error::PurseTransferError);
        }
        _ => runtime::revert(Error::UnknownProxyCommand),
    }
}
