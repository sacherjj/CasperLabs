use alloc::string::{String, ToString};
use core::convert::TryFrom;

use crate::ApiError;

/// System contracts used to query host for system contract ref
#[derive(Debug, Copy, Clone, PartialEq)]
pub enum SystemContractType {
    Mint,
    ProofOfStake,
}

impl Into<u32> for SystemContractType {
    fn into(self) -> u32 {
        match self {
            SystemContractType::Mint => 0,
            SystemContractType::ProofOfStake => 1,
        }
    }
}

impl TryFrom<u32> for SystemContractType {
    type Error = ApiError;
    fn try_from(value: u32) -> Result<SystemContractType, Self::Error> {
        match value {
            0 => Ok(SystemContractType::Mint),
            1 => Ok(SystemContractType::ProofOfStake),
            _ => Err(ApiError::InvalidSystemContract),
        }
    }
}

impl ToString for SystemContractType {
    fn to_string(&self) -> String {
        match *self {
            SystemContractType::Mint => "mint".to_string(),
            SystemContractType::ProofOfStake => "pos".to_string(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn get_index_of_mint_contract() {
        let index: u32 = SystemContractType::Mint.into();
        assert_eq!(index, 0u32);
        assert_eq!(SystemContractType::Mint.to_string(), "mint");
    }

    #[test]
    fn get_index_of_pos_contract() {
        let index: u32 = SystemContractType::ProofOfStake.into();
        assert_eq!(index, 1u32);
        assert_eq!(SystemContractType::ProofOfStake.to_string(), "pos");
    }

    #[test]
    fn create_mint_variant_from_int() {
        let mint = SystemContractType::try_from(0).ok().unwrap();
        assert_eq!(mint, SystemContractType::Mint);
    }

    #[test]
    fn create_pos_variant_from_int() {
        let pos = SystemContractType::try_from(0).ok().unwrap();
        assert_eq!(pos, SystemContractType::Mint);
    }

    #[test]
    fn create_unknown_system_contract_variant() {
        assert!(SystemContractType::try_from(2).is_err());
        assert!(SystemContractType::try_from(3).is_err());
        assert!(SystemContractType::try_from(10).is_err());
        assert!(SystemContractType::try_from(u32::max_value()).is_err());
    }
}
