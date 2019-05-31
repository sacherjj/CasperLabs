use cl_std::contract_api;
use cl_std::key::Key;
use core::convert::TryFrom;

pub struct WithdrawId([u8; 32]);
pub struct DepositId([u8; 32]);

impl WithdrawId {
    pub fn raw_id(&self) -> [u8; 32] {
        self.0
    }

    pub fn lookup(&self) -> Option<Key> {
        contract_api::read_local(self.raw_id())
    }
}

impl DepositId {
    pub fn raw_id(&self) -> [u8; 32] {
        self.0
    }

    pub fn lookup(&self) -> Option<Key> {
        contract_api::read_local(self.raw_id())
    }
}

impl TryFrom<Key> for WithdrawId {
    type Error = ();

    fn try_from(k: Key) -> Result<WithdrawId, ()> {
        if contract_api::is_valid(k) {
            match k {
                Key::URef(id, _) => Ok(WithdrawId(id)),
                _ => Err(()),
            }
        } else {
            Err(())
        }
    }
}

impl TryFrom<Key> for DepositId {
    type Error = ();

    fn try_from(k: Key) -> Result<DepositId, ()> {
        match k {
            Key::URef(id, _) => Ok(DepositId(id)),
            _ => Err(()),
        }
    }
}
