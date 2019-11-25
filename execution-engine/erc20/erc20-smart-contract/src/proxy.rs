use crate::api::Api;

use alloc::vec;

use contract_ffi::contract_api::runtime;
use contract_ffi::value::U512;

use crate::error::Error;

#[no_mangle]
pub extern "C" fn erc20_proxy() {
    let token_ref = Api::destination_contract();
    match Api::from_args_in_proxy() {
        Api::Transfer(address, amount) => {
            runtime::call_contract::<_, ()>(token_ref.clone(), &("transfer", address, amount), &vec![]);
        },
        Api::AssertBalance(address, expected_amount) => {
            let balance = runtime::call_contract::<_, U512>(token_ref.clone(), &("balance_of", address), &vec![]);
            if balance != expected_amount {
                runtime::revert(Error::BalanceAssertionFailure)
            }
        }
       Api::AssertTotalSupply(expected_total_supply) => {
            let total_supply = runtime::call_contract::<_, U512>(token_ref.clone(), &("total_supply",), &vec![]);
            if total_supply != expected_total_supply {
                runtime::revert(Error::TotalSupplyAssertionFailure)
            }
        }
        _ => runtime::revert(Error::UnknownProxyCommand)
    }
}
