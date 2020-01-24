use crate::{VestingError, VestingTrait};
extern crate alloc;
use alloc::{
    collections::btree_map::BTreeMap,
    string::{String, ToString},
};

type Amount = u64;
type Time = u64;

struct Vault {
    amounts: BTreeMap<String, Amount>,
    times: BTreeMap<String, Time>,
    booleans: BTreeMap<String, bool>,
    current_time: Time,
}

impl Vault {
    fn new(conf: VestingConfig) -> Vault {
        let mut vault = Vault {
            amounts: BTreeMap::new(),
            times: BTreeMap::new(),
            booleans: BTreeMap::new(),
            current_time: 0,
        };
        vault.init(
            conf.cliff_time,
            conf.cliff_amount,
            conf.drip_period,
            conf.drip_amount,
            conf.total_amount,
            conf.admin_release_period,
        );
        vault
    }

    fn set_current_time(&mut self, new_time: Time) {
        self.current_time = new_time;
    }
}

impl VestingTrait<Amount, Time> for Vault {
    fn set_amount(&mut self, name: &str, value: Amount) {
        self.amounts.insert(name.to_string(), value);
    }
    fn get_amount(&self, name: &str) -> Amount {
        self.amounts.get(&name.to_string()).cloned().unwrap()
    }
    fn set_time(&mut self, name: &str, value: Time) {
        self.times.insert(name.to_string(), value);
    }
    fn get_time(&self, name: &str) -> Time {
        self.times.get(&name.to_string()).cloned().unwrap()
    }
    fn set_boolean(&mut self, name: &str, value: bool) {
        self.booleans.insert(name.to_string(), value);
    }
    fn get_boolean(&self, name: &str) -> bool {
        self.booleans.get(&name.to_string()).cloned().unwrap()
    }
    fn current_time(&self) -> Time {
        self.current_time
    }
}

struct VestingConfig {
    cliff_time: Time,
    cliff_amount: Amount,
    drip_period: Time,
    drip_amount: Amount,
    total_amount: Amount,
    admin_release_period: Time,
}

impl Default for VestingConfig {
    fn default() -> VestingConfig {
        VestingConfig {
            cliff_time: 10,
            cliff_amount: 2,
            drip_period: 3,
            drip_amount: 5,
            total_amount: 1000,
            admin_release_period: 123,
        }
    }
}

#[test]
fn test_pausing() {
    let mut vault = Vault::new(Default::default());
    assert_eq!(vault.total_paused_time(), 0);
    vault.set_current_time(5);
    assert_eq!(vault.total_paused_time(), 0);
    let _ = vault.pause();
    vault.set_current_time(8);
    let _ = vault.unpause();
    assert_eq!(vault.total_paused_time(), 3);
    vault.set_current_time(10);
    let _ = vault.pause();
    vault.set_current_time(100);
    let _ = vault.unpause();
    assert_eq!(vault.total_paused_time(), 93);
}

#[test]
fn test_pausing_already_paused_error() {
    let mut vault = Vault::new(Default::default());
    let first = vault.pause();
    assert!(first.is_ok());
    let second = vault.pause();
    let expected = VestingError::AlreadyPaused;
    assert_eq!(second.unwrap_err(), expected);
}

#[test]
fn test_pausing_already_unpaused_error() {
    let mut vault = Vault::new(Default::default());
    let result = vault.unpause();
    let expected = VestingError::AlreadyUnpaused;
    assert_eq!(result.unwrap_err(), expected);
}

#[test]
fn test_availible_no_pause() {
    let mut vault = Vault::new(Default::default());
    assert_eq!(vault.available_amount(), 0);
    vault.set_current_time(10);
    assert_eq!(vault.available_amount(), 2);
    vault.set_current_time(12);
    assert_eq!(vault.available_amount(), 2);
    vault.set_current_time(13);
    assert_eq!(vault.available_amount(), 2 + 5);
    vault.set_current_time(10 + 50 * 3 + 1);
    assert_eq!(vault.available_amount(), 2 + 50 * 5);
    vault.set_current_time(607);
    assert_eq!(vault.available_amount(), 997);
    vault.set_current_time(608);
    assert_eq!(vault.available_amount(), 997);
    vault.set_current_time(609);
    assert_eq!(vault.available_amount(), 997);
    vault.set_current_time(610);
    assert_eq!(vault.available_amount(), 1000);
}

#[test]
fn test_availible_with_pause() {
    let mut vault = Vault::new(Default::default());
    vault.set_current_time(5);
    let _ = vault.pause();
    vault.set_current_time(8);
    let _ = vault.unpause(); // total paused time = 3
    assert_eq!(vault.available_amount(), 0);
    vault.set_current_time(11);
    assert_eq!(vault.available_amount(), 0);
    vault.set_current_time(12);
    assert_eq!(vault.available_amount(), 0);
    vault.set_current_time(13);
    assert_eq!(vault.available_amount(), 2);
    vault.set_current_time(20);
    assert_eq!(vault.available_amount(), 12);
    let _ = vault.pause();
    vault.set_current_time(37);
    assert_eq!(vault.available_amount(), 12);
    let _ = vault.unpause(); // total paused time = 20
    assert_eq!(vault.available_amount(), 12);
    vault.set_current_time(629);
    assert_eq!(vault.available_amount(), 997);
    vault.set_current_time(630);
    assert_eq!(vault.available_amount(), 1000);
}

#[test]
fn test_withdraw_not_enough_balance_error() {
    let mut vault = Vault::new(Default::default());
    vault.set_current_time(11);
    let result = vault.withdraw(100);
    let expected = VestingError::NotEnoughBalance;
    assert_eq!(result.unwrap_err(), expected);
}

#[test]
fn test_withdraw() {
    let mut vault = Vault::new(Default::default());
    vault.set_current_time(11);
    let result = vault.withdraw(1);
    assert!(result.is_ok());
    assert_eq!(vault.available_amount(), 1);
    let _ = vault.pause();
    vault.set_current_time(16);
    let result = vault.withdraw(1);
    assert!(result.is_ok());
    assert_eq!(vault.available_amount(), 0);
    let _ = vault.unpause();
    vault.set_current_time(18);
    assert_eq!(vault.available_amount(), 5);
    let result = vault.withdraw(4);
    assert!(result.is_ok());
    assert_eq!(vault.available_amount(), 1);
}

#[test]
fn test_admin_release() {
    let mut vault = Vault::new(Default::default());
    let _ = vault.pause();
    vault.set_current_time(123);
    let result = vault.admin_release().unwrap();
    assert_eq!(result, 1000);
    assert_eq!(vault.available_amount(), 0);
    vault.set_current_time(2000);
    assert_eq!(vault.available_amount(), 0);
}

#[test]
fn test_admin_release_not_paused_error() {
    let mut vault = Vault::new(Default::default());
    vault.set_current_time(123);
    let result = vault.admin_release().unwrap_err();
    assert_eq!(result, VestingError::AdminReleaseErrorNotPaused);
}

#[test]
fn test_admin_release_not_enough_time_elapsed_error() {
    let mut vault = Vault::new(Default::default());
    vault.set_current_time(10);
    let _ = vault.pause();
    vault.set_current_time(50);
    let result = vault.admin_release().unwrap_err();
    assert_eq!(result, VestingError::AdminReleaseErrorNotEnoughTimeElapsed);
}

#[test]
fn test_admin_release_nothing_to_withdraw_error() {
    let mut vault = Vault::new(Default::default());
    vault.set_current_time(30000);
    let _ = vault.pause();
    vault.set_current_time(31000);
    let _ = vault.withdraw(1000);
    let result = vault.admin_release().unwrap_err();
    assert_eq!(result, VestingError::AdminReleaseErrorNothingToWithdraw);
}
