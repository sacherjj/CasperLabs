use cl_std::contract_api;
use cl_std::system_contracts::mint::purse_id::PurseIdError;
use cl_std::uref::URef;

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
