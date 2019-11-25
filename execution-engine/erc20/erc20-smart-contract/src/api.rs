use alloc::string::String;

use contract_ffi::bytesrepr::FromBytes;
use contract_ffi::contract_api::account::PublicKey;
use contract_ffi::contract_api::runtime;
use contract_ffi::contract_api::Error as ApiError;
use contract_ffi::contract_api::ContractRef;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::U512;

use crate::error::Error;

pub enum Api {
    Deploy(String, U512),
    InitErc20(U512),
    BalanceOf(PublicKey),
    TotalSupply,
    Transfer(PublicKey, U512),
    AssertBalance(PublicKey, U512),
    AssertTotalSupply(U512)
}

fn get_arg<T: FromBytes>(i: u32) -> T {
    runtime::get_arg(i)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument)
}

impl Api {
    pub fn from_args() -> Api { 
        Self::from_args_with_shift(0) 
    }
    pub fn from_args_in_proxy() -> Api { 
        Self::from_args_with_shift(1) 
    }
    pub fn from_args_with_shift(arg_shift: u32) -> Api {
        let method_name: String = get_arg(arg_shift + 0);
        match method_name.as_str() {
            "deploy" => {
                let token_name = get_arg(arg_shift + 1);
                let initial_balance = get_arg(arg_shift + 2);
                Api::Deploy(token_name, initial_balance)
            },
            "init_erc20" => {
                let amount = get_arg(arg_shift + 1);
                Api::InitErc20(amount)
            },
            "balance_of" => {
                let public_key: PublicKey = get_arg(arg_shift + 1);
                Api::BalanceOf(public_key)
            },
            "total_supply" => Api::TotalSupply,
            "transfer" => {
                let address = get_arg(arg_shift + 1);
                let amount = get_arg(arg_shift + 2);
                Api::Transfer(address, amount)
            },
            "assert_balance" => {
                let address = get_arg(arg_shift + 1);
                let amount = get_arg(arg_shift + 2);
                Api::AssertBalance(address, amount)   
            },
            "assert_total_supply" => {
                let total_supply = get_arg(arg_shift + 1);
                Api::AssertTotalSupply(total_supply)
            },
            _ => runtime::revert(Error::UnknownApiCommand)
        }
    }
    pub fn destination_contract() -> ContractRef {
        ContractRef::Hash(get_arg(0))
    }
}


