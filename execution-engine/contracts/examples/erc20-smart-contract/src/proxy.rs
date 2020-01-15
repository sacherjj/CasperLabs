use crate::{
    api::{self, Api},
    error::Error,
};
use contract::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::U512;

#[no_mangle]
pub extern "C" fn erc20_proxy() {
    let token_ref = Api::destination_contract();
    match Api::from_args_in_proxy() {
        Api::Transfer(recipient, amount) => {
            runtime::call_contract::<_, ()>(token_ref, (api::TRANSFER, recipient, amount));
        }
        Api::TransferFrom(owner, recipient, amount) => {
            runtime::call_contract::<_, ()>(
                token_ref,
                (api::TRANSFER_FROM, owner, recipient, amount),
            );
        }
        Api::Approve(spender, amount) => {
            runtime::call_contract::<_, ()>(token_ref, (api::APPROVE, spender, amount));
        }
        Api::AssertBalance(address, expected_amount) => {
            let balance = runtime::call_contract::<_, U512>(token_ref, (api::BALANCE_OF, address));
            if expected_amount != balance {
                runtime::revert(Error::BalanceAssertionFailure)
            }
        }
        Api::AssertTotalSupply(expected_total_supply) => {
            let total_supply = runtime::call_contract::<_, U512>(token_ref, (api::TOTAL_SUPPLY,));
            if expected_total_supply != total_supply {
                runtime::revert(Error::TotalSupplyAssertionFailure)
            }
        }
        Api::AssertAllowance(owner, spender, expected_amount) => {
            let allowance =
                runtime::call_contract::<_, U512>(token_ref, (api::ALLOWANCE, owner, spender));
            if expected_amount != allowance {
                runtime::revert(Error::AllowanceAssertionFailure)
            }
        }
        Api::BuyProxy(clx_amount) => {
            let main_purse = account::get_main_purse();
            let new_purse = system::create_purse();
            system::transfer_from_purse_to_purse(main_purse, new_purse, clx_amount)
                .unwrap_or_revert_with(Error::PurseTransferError);
            runtime::call_contract::<_, ()>(token_ref, (api::BUY, new_purse));
        }
        Api::SellProxy(tokens_amount) => {
            let new_purse = system::create_purse();
            runtime::call_contract::<_, ()>(token_ref, (api::SELL, new_purse, tokens_amount));
            let main_purse = account::get_main_purse();
            system::transfer_from_purse_to_purse(new_purse, main_purse, tokens_amount)
                .unwrap_or_revert_with(Error::PurseTransferError);
        }
        _ => runtime::revert(Error::UnknownProxyCommand),
    }
}
