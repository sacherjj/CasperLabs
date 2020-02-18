use alloc::string::String;

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::{account::PublicKey, bytesrepr::FromBytes, CLTyped, ContractRef};

use crate::error::Error;

pub const START: &str = "start";
pub const MOVE: &str = "move";
pub const CONCEDE: &str = "concede";

pub enum Api {
    Start(PublicKey, PublicKey),
    Move(u32, u32),
    Concede,
}

impl Api {
    pub fn from_args() -> Api {
        Self::from_args_with_shift(0)
    }

    pub fn from_args_in_proxy() -> Api {
        Self::from_args_with_shift(1)
    }

    pub fn from_args_with_shift(start_index: u32) -> Api {
        let method_name: String = get_arg(start_index);
        match method_name.as_str() {
            START => {
                let x_player = get_arg(start_index + 1);
                let o_player = get_arg(start_index + 2);
                Api::Start(x_player, o_player)
            }
            MOVE => {
                let row_position: u32 = get_arg(start_index + 1);
                let column_position: u32 = get_arg(start_index + 2);
                Api::Move(row_position, column_position)
            }
            CONCEDE => Api::Concede,
            _ => runtime::revert(Error::UnknownApiCommand),
        }
    }

    pub fn destination_contract() -> ContractRef {
        ContractRef::Hash(get_arg(0))
    }
}

fn get_arg<T: CLTyped + FromBytes>(i: u32) -> T {
    runtime::get_arg(i)
        .unwrap_or_revert_with(Error::missing_argument(i))
        .unwrap_or_revert_with(Error::invalid_argument(i))
}
