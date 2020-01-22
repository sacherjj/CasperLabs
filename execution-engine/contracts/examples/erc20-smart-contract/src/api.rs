use alloc::string::String;

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::{
    account::{PublicKey, PurseId},
    bytesrepr::FromBytes,
    CLTyped, ContractRef, U512,
};

use crate::error::Error;

pub const DEPLOY: &str = "deploy";
pub const INIT_ERC20: &str = "init_erc20";
pub const BALANCE_OF: &str = "balance_of";
pub const TOTAL_SUPPLY: &str = "total_supply";
pub const TRANSFER: &str = "transfer";
pub const TRANSFER_FROM: &str = "transfer_from";
pub const APPROVE: &str = "approve";
pub const ASSERT_BALANCE: &str = "assert_balance";
pub const ASSERT_TOTAL_SUPPLY: &str = "assert_total_supply";
pub const ASSERT_ALLOWANCE: &str = "assert_allowance";
pub const ALLOWANCE: &str = "allowance";
pub const BUY_PROXY: &str = "buy_proxy";
pub const BUY: &str = "buy";
pub const SELL_PROXY: &str = "sell_proxy";
pub const SELL: &str = "sell";

pub enum Api {
    Deploy(String, U512),
    InitErc20(U512),
    BalanceOf(PublicKey),
    TotalSupply,
    Transfer(PublicKey, U512),
    TransferFrom(PublicKey, PublicKey, U512),
    Approve(PublicKey, U512),
    Allowance(PublicKey, PublicKey),
    AssertBalance(PublicKey, U512),
    AssertTotalSupply(U512),
    AssertAllowance(PublicKey, PublicKey, U512),
    BuyProxy(U512),
    Buy(PurseId),
    SellProxy(U512),
    Sell(PurseId, U512),
}

fn get_arg<T: CLTyped + FromBytes>(i: u32) -> T {
    runtime::get_arg(i)
        .unwrap_or_revert_with(Error::missing_argument(i))
        .unwrap_or_revert_with(Error::invalid_argument(i))
}

impl Api {
    pub fn from_args() -> Api {
        Self::from_args_with_shift(0)
    }

    pub fn from_args_in_proxy() -> Api {
        Self::from_args_with_shift(1)
    }

    fn from_args_with_shift(arg_shift: u32) -> Api {
        let method_name: String = get_arg(arg_shift);
        match method_name.as_str() {
            DEPLOY => {
                let token_name = get_arg(arg_shift + 1);
                let initial_balance = get_arg(arg_shift + 2);
                Api::Deploy(token_name, initial_balance)
            }
            INIT_ERC20 => {
                let amount = get_arg(arg_shift + 1);
                Api::InitErc20(amount)
            }
            BALANCE_OF => {
                let public_key: PublicKey = get_arg(arg_shift + 1);
                Api::BalanceOf(public_key)
            }
            TOTAL_SUPPLY => Api::TotalSupply,
            TRANSFER => {
                let recipient = get_arg(arg_shift + 1);
                let amount = get_arg(arg_shift + 2);
                Api::Transfer(recipient, amount)
            }
            TRANSFER_FROM => {
                let owner = get_arg(arg_shift + 1);
                let recipient = get_arg(arg_shift + 2);
                let amount = get_arg(arg_shift + 3);
                Api::TransferFrom(owner, recipient, amount)
            }
            APPROVE => {
                let spender = get_arg(arg_shift + 1);
                let amount = get_arg(arg_shift + 2);
                Api::Approve(spender, amount)
            }
            ASSERT_BALANCE => {
                let address = get_arg(arg_shift + 1);
                let amount = get_arg(arg_shift + 2);
                Api::AssertBalance(address, amount)
            }
            ASSERT_TOTAL_SUPPLY => {
                let total_supply = get_arg(arg_shift + 1);
                Api::AssertTotalSupply(total_supply)
            }
            ASSERT_ALLOWANCE => {
                let owner = get_arg(arg_shift + 1);
                let spender = get_arg(arg_shift + 2);
                let amount = get_arg(arg_shift + 3);
                Api::AssertAllowance(owner, spender, amount)
            }
            ALLOWANCE => {
                let owner = get_arg(arg_shift + 1);
                let spender = get_arg(arg_shift + 2);
                Api::Allowance(owner, spender)
            }
            BUY_PROXY => {
                let clx_amount = get_arg(arg_shift + 1);
                Api::BuyProxy(clx_amount)
            }
            BUY => {
                let purse = get_arg(arg_shift + 1);
                Api::Buy(purse)
            }
            SELL_PROXY => {
                let token_amount = get_arg(arg_shift + 1);
                Api::SellProxy(token_amount)
            }
            SELL => {
                let purse = get_arg(arg_shift + 1);
                let amount = get_arg(arg_shift + 2);
                Api::Sell(purse, amount)
            }
            _ => runtime::revert(Error::UnknownApiCommand),
        }
    }

    pub fn destination_contract() -> ContractRef {
        ContractRef::Hash(get_arg(0))
    }
}
