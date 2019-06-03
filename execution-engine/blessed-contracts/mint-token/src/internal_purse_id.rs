use cl_std::contract_api;
use cl_std::key::{AccessRights, Key};

#[derive(Debug, Copy, Clone)]
pub enum PurseIdError {
    InvalidKey,
    InvalidKeyVariant,
    InvalidAccessRights(Option<AccessRights>),
}

pub struct WithdrawId([u8; 32]);

impl WithdrawId {
    pub fn from_key(key: Key) -> Result<Self, PurseIdError> {
        if !contract_api::is_valid(key) {
            return Err(PurseIdError::InvalidKey);
        }

        match key {
            Key::URef(id, Some(access_rights)) if access_rights.is_writeable() => {
                Ok(WithdrawId(id))
            }
            Key::URef(_, maybe_access_rights) => {
                Err(PurseIdError::InvalidAccessRights(maybe_access_rights))
            }
            _ => Err(PurseIdError::InvalidKeyVariant),
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
            Key::URef(id, Some(access_rights)) if access_rights.is_addable() => Ok(DepositId(id)),
            Key::URef(_, maybe_access_rights) => {
                Err(PurseIdError::InvalidAccessRights(maybe_access_rights))
            }
            _ => Err(PurseIdError::InvalidKeyVariant),
        }
    }

    pub fn raw_id(&self) -> [u8; 32] {
        self.0
    }
}
