use crate::{api::Api, error::Error};
use contract::{
    contract_api::{runtime, storage, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    account::{PublicKey, PurseId},
    U256, U512,
};
use vesting_logic::{VestingError, VestingTrait};

pub const INIT_FLAG_KEY: [u8; 32] = [1u8; 32];
pub const ADMIN_KEY: &str = "admin_account";
pub const RECIPIENT_KEY: &str = "recipient_account";
pub const PURSE_NAME: &str = "vesting_main_purse";

type Amount = U512;
type Time = U512;

struct VestingContrat;

impl VestingTrait<Amount, Time> for VestingContrat {
    fn set_amount(&mut self, name: &str, value: Amount) {
        storage::write_local(name, value);
    }
    fn get_amount(&self, name: &str) -> Amount {
        storage::read_local(&name)
            .unwrap_or_revert_with(Error::MissingLocalKey)
            .unwrap_or_revert_with(Error::UnexpectedType)
    }
    fn set_time(&mut self, name: &str, value: Time) {
        storage::write_local(name, value);
    }
    fn get_time(&self, name: &str) -> Time {
        storage::read_local(&name)
            .unwrap_or_revert_with(Error::MissingLocalKey)
            .unwrap_or_revert_with(Error::UnexpectedType)
    }
    fn set_boolean(&mut self, name: &str, value: bool) {
        let val = if value { Amount::one() } else { Amount::zero() };
        self.set_amount(name, val);
    }
    fn get_boolean(&self, name: &str) -> bool {
        let val = self.get_amount(name);
        val == Amount::one()
    }
    fn current_time(&self) -> Time {
        let time: u64 = runtime::get_blocktime().into();
        time.into()
    }
}

fn constructor() {
    let mut vault = VestingContrat;
    match Api::from_args() {
        Api::Init(admin, recipient, vesting_config) => {
            set_admin_account(admin);
            set_recipient_account(recipient);
            vault.init(
                vesting_config.cliff_time,
                vesting_config.cliff_amount,
                vesting_config.drip_period,
                vesting_config.drip_amount,
                vesting_config.total_amount,
                vesting_config.admin_release_period,
            );
        }
        _ => runtime::revert(Error::UnknownConstructorCommand),
    }
}

fn entry_point() {
    let mut vault = VestingContrat;
    match Api::from_args() {
        Api::Pause => {
            verify_admin_account();
            match vault.pause() {
                Ok(()) => {}
                Err(VestingError::AlreadyPaused) => runtime::revert(Error::AlreadyPaused),
                _ => runtime::revert(Error::UnexpectedVestingError),
            }
        }
        Api::Unpause => {
            verify_admin_account();
            match vault.unpause() {
                Ok(()) => {}
                Err(VestingError::AlreadyUnpaused) => runtime::revert(Error::AlreadyUnpaused),
                _ => runtime::revert(Error::UnexpectedVestingError),
            }
        }
        Api::Withdraw(purse, amount) => {
            verify_recipient_account();
            match vault.withdraw(amount) {
                Ok(()) => transfer_out_clx_to_purse(purse, amount),
                Err(VestingError::NotEnoughBalance) => runtime::revert(Error::NotEnoughBalance),
                _ => runtime::revert(Error::UnexpectedVestingError),
            }
        }
        Api::AdminRelease(purse) => {
            verify_admin_account();
            match vault.admin_release() {
                Ok(amount) => transfer_out_clx_to_purse(purse, amount),
                Err(VestingError::AdminReleaseErrorNotPaused) => runtime::revert(Error::NotPaused),
                Err(VestingError::AdminReleaseErrorNothingToWithdraw) => {
                    runtime::revert(Error::NothingToWithdraw)
                }
                Err(VestingError::AdminReleaseErrorNotEnoughTimeElapsed) => {
                    runtime::revert(Error::NotEnoughTimeElapsed)
                }
                _ => runtime::revert(Error::UnexpectedVestingError),
            }
        }
        _ => runtime::revert(Error::UnknownVestingCallCommand),
    }
}

fn is_not_initialized() -> bool {
    let flag: Option<i32> = storage::read_local(&INIT_FLAG_KEY).unwrap_or_revert();
    flag.is_none()
}

fn mark_as_initialized() {
    storage::write_local(INIT_FLAG_KEY, 1);
}

fn set_admin_account(admin: PublicKey) {
    set_account(ADMIN_KEY, admin);
}

fn get_admin_account() -> PublicKey {
    get_account(ADMIN_KEY)
}

fn set_recipient_account(recipient: PublicKey) {
    set_account(RECIPIENT_KEY, recipient);
}

fn get_recipient_account() -> PublicKey {
    get_account(RECIPIENT_KEY)
}

fn set_account(key: &str, value: PublicKey) {
    let val: U256 = value.value().into();
    storage::write_local(key, val);
}

fn get_account(key: &str) -> PublicKey {
    let val: U256 = storage::read_local(&key)
        .unwrap_or_revert_with(Error::MissingLocalKey)
        .unwrap_or_revert_with(Error::UnexpectedType);
    PublicKey::new(val.into())
}

fn verify_admin_account() {
    let admin = get_admin_account();
    let caller = runtime::get_caller();
    if admin != caller {
        runtime::revert(Error::NotTheAdminAccount);
    }
}

fn verify_recipient_account() {
    let recipient = get_recipient_account();
    let caller = runtime::get_caller();
    if recipient != caller {
        runtime::revert(Error::NotTheRecipientAccount);
    }
}

fn transfer_out_clx_to_purse(purse: PurseId, amount: U512) {
    let local_purse = local_purse();
    system::transfer_from_purse_to_purse(local_purse, purse, amount)
        .unwrap_or_revert_with(Error::PurseTransferError);
}

fn local_purse() -> PurseId {
    let key = runtime::get_key(PURSE_NAME).unwrap_or_revert_with(Error::LocalPurseKeyMissing);
    let uref = key.as_uref().unwrap_or_revert_with(Error::UnexpectedType);
    PurseId::new(*uref)
}

#[no_mangle]
pub extern "C" fn vesting() {
    if is_not_initialized() {
        constructor();
        mark_as_initialized();
    } else {
        entry_point();
    }
}
