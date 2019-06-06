use core::fmt;

use cl_std::contract_api;
use cl_std::key::Key;
use cl_std::uref::AccessRights;

#[derive(Debug, Copy, Clone)]
pub enum PurseIdError {
    InvalidKey,
    InvalidKeyVariant(Key),
    InvalidAccessRights(Option<AccessRights>),
}

impl fmt::Display for PurseIdError {
    fn fmt(&self, f: &mut fmt::Formatter) -> Result<(), fmt::Error> {
        match self {
            PurseIdError::InvalidKey => write!(f, "invalid key"),
            PurseIdError::InvalidKeyVariant(invalid_key_variant) => {
                write!(f, "invalid key variant: {:?}", invalid_key_variant)
            }
            PurseIdError::InvalidAccessRights(maybe_access_rights) => {
                write!(f, "invalid access rights: {:?}", maybe_access_rights)
            }
        }
    }
}

pub struct WithdrawId([u8; 32]);

impl WithdrawId {
    pub fn from_key(key: Key) -> Result<Self, PurseIdError> {
        if !contract_api::is_valid(key) {
            return Err(PurseIdError::InvalidKey);
        }

        match key {
            Key::URef(uref) if uref.is_writeable() => Ok(WithdrawId(uref.id())),
            Key::URef(uref) => Err(PurseIdError::InvalidAccessRights(uref.access_rights())),
            key => Err(PurseIdError::InvalidKeyVariant(key)),
        }
    }

    pub fn raw_id(&self) -> [u8; 32] {
        self.0
    }
}

pub struct DepositId([u8; 32]);

impl DepositId {
    pub fn from_key(key: Key) -> Result<Self, PurseIdError> {
        if !contract_api::is_valid(key) {
            return Err(PurseIdError::InvalidKey);
        }

        match key {
            Key::URef(uref) if uref.is_addable() => Ok(DepositId(uref.id())),
            Key::URef(uref) => Err(PurseIdError::InvalidAccessRights(uref.access_rights())),
            key => Err(PurseIdError::InvalidKeyVariant(key)),
        }
    }

    pub fn raw_id(&self) -> [u8; 32] {
        self.0
    }
}
