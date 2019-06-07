use core::fmt;

use cl_std::contract_api;
use cl_std::uref::{AccessRights, URef};

#[derive(Debug, Copy, Clone)]
pub enum PurseIdError {
    InvalidURef,
    InvalidAccessRights(Option<AccessRights>),
}

impl fmt::Display for PurseIdError {
    fn fmt(&self, f: &mut fmt::Formatter) -> Result<(), fmt::Error> {
        match self {
            PurseIdError::InvalidURef => write!(f, "invalid uref"),
            PurseIdError::InvalidAccessRights(maybe_access_rights) => {
                write!(f, "invalid access rights: {:?}", maybe_access_rights)
            }
        }
    }
}

pub struct WithdrawId([u8; 32]);

impl WithdrawId {
    pub fn from_uref(uref: URef) -> Result<Self, PurseIdError> {
        if !contract_api::is_valid(uref) {
            return Err(PurseIdError::InvalidURef);
        }

        if uref.is_writeable() {
            Ok(WithdrawId(uref.addr()))
        } else {
            Err(PurseIdError::InvalidAccessRights(uref.access_rights()))
        }
    }

    pub fn raw_id(&self) -> [u8; 32] {
        self.0
    }
}

pub struct DepositId([u8; 32]);

impl DepositId {
    pub fn from_uref(uref: URef) -> Result<Self, PurseIdError> {
        if !contract_api::is_valid(uref) {
            return Err(PurseIdError::InvalidURef);
        }

        if uref.is_addable() {
            Ok(DepositId(uref.addr()))
        } else {
            Err(PurseIdError::InvalidAccessRights(uref.access_rights()))
        }
    }

    pub fn raw_id(&self) -> [u8; 32] {
        self.0
    }
}
