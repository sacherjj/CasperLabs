use crate::contract_api::Error;
use alloc::string::{String, ToString};
use core::convert::TryFrom;

/// System contracts used to query host for system contract ref
#[derive(Debug, Copy, Clone, PartialEq)]
pub enum SystemContract {
    Mint,
    ProofOfStake,
}

impl Into<u32> for SystemContract {
    fn into(self) -> u32 {
        match self {
            SystemContract::Mint => 0,
            SystemContract::ProofOfStake => 1,
        }
    }
}

impl TryFrom<u32> for SystemContract {
    type Error = Error;
    fn try_from(value: u32) -> Result<SystemContract, Self::Error> {
        match value {
            0 => Ok(SystemContract::Mint),
            1 => Ok(SystemContract::ProofOfStake),
            _ => Err(Error::InvalidSystemContract),
        }
    }
}

impl ToString for SystemContract {
    fn to_string(&self) -> String {
        match *self {
            SystemContract::Mint => "mint".to_string(),
            SystemContract::ProofOfStake => "pos".to_string(),
        }
    }
}

#[test]
fn get_index_of_mint_contract() {
    let index: u32 = SystemContract::Mint.into();
    assert_eq!(index, 0u32);
    assert_eq!(SystemContract::Mint.to_string(), "mint");
}

#[test]
fn get_index_of_pos_contract() {
    let index: u32 = SystemContract::ProofOfStake.into();
    assert_eq!(index, 1u32);
    assert_eq!(SystemContract::ProofOfStake.to_string(), "pos");
}

#[test]
fn create_mint_variant_from_int() {
    let mint = SystemContract::try_from(0).ok().unwrap();
    assert_eq!(mint, SystemContract::Mint);
}

#[test]
fn create_pos_variant_from_int() {
    let pos = SystemContract::try_from(0).ok().unwrap();
    assert_eq!(pos, SystemContract::Mint);
}

#[test]
fn create_unknown_system_contract_variant() {
    assert!(SystemContract::try_from(2).is_err());
    assert!(SystemContract::try_from(3).is_err());
    assert!(SystemContract::try_from(10).is_err());
    assert!(SystemContract::try_from(u32::max_value()).is_err());
}
