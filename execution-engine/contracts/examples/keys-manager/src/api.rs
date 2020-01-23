use alloc::string::String;
use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::{
    account::{PublicKey, Weight},
    bytesrepr::FromBytes,
    CLTyped,
};

use crate::error::Error;

pub const SET_KEY_WEIGHT: &str = "set_key_weight";
pub const SET_DEPLOYMENT_THRESHOLD: &str = "set_deployment_threshold";
pub const SET_KEY_MANAGEMENT_THRESHOLD: &str = "set_key_management_threshold";

pub enum Api {
    SetKeyWeight(PublicKey, Weight),
    SetDeploymentThreshold(Weight),
    SetKeyManagementThreshold(Weight),
}

fn get_arg<T: CLTyped + FromBytes>(i: u32) -> T {
    runtime::get_arg(i)
        .unwrap_or_revert_with(Error::missing_argument(i))
        .unwrap_or_revert_with(Error::invalid_argument(i))
}

impl Api {
    pub fn from_args() -> Api {
        let method_name: String = get_arg(0);
        match method_name.as_str() {
            SET_KEY_WEIGHT => {
                let key = get_arg(1);
                let weight: u8 = get_arg(2);
                Api::SetKeyWeight(key, Weight::new(weight))
            }
            SET_DEPLOYMENT_THRESHOLD => {
                let threshold: u8 = get_arg(1);
                Api::SetDeploymentThreshold(Weight::new(threshold))
            }
            SET_KEY_MANAGEMENT_THRESHOLD => {
                let threshold: u8 = get_arg(1);
                Api::SetKeyManagementThreshold(Weight::new(threshold))
            }
            _ => runtime::revert(Error::UnknownApiCommand),
        }
    }
}
