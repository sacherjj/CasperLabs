#![no_std]

extern crate num_traits;
use core::{
    cmp::{self, Ord},
    ops::{Add, Div, Mul, Sub},
};
use num_traits::{One, Zero};

#[cfg(test)]
mod tests;

#[derive(PartialEq, Debug)]
pub enum VestingError {
    NotEnoughBalance,
    AdminReleaseErrorNotPaused,
    AdminReleaseErrorNothingToWithdraw,
    AdminReleaseErrorNotEnoughTimeElapsed,
    AlreadyPaused,
    AlreadyUnpaused,
}

pub mod key {
    pub const CLIFF_TIME: &str = "cliff_time";
    pub const CLIFF_AMOUNT: &str = "cliff_amount";
    pub const DRIP_PERIOD: &str = "drip_period";
    pub const DRIP_AMOUNT: &str = "drip_amount";
    pub const TOTAL_AMOUNT: &str = "total_amount";
    pub const RELEASED_AMOUNT: &str = "released_amount";
    pub const ADMIN_RELEASE_PERIOD: &str = "admin_release_period";
    pub const PAUSE_FLAG: &str = "is_paused";
    pub const ON_PAUSE_PERIOD: &str = "on_pause_period";
    pub const LAST_PAUSE_TIME: &str = "last_pause_time";
}

pub trait VestingTrait<
    Amount: Copy + Zero + One + Add<Output = Amount> + Sub<Output = Amount> + Mul<Output = Amount> + Ord,
    Time: Copy + Zero + PartialOrd + Sub<Output = Time> + Div<Output = Amount>,
>
{
    fn init(
        &mut self,
        cliff_time: Time,
        cliff_amount: Amount,
        drip_period: Time,
        drip_amount: Amount,
        total_amount: Amount,
        admin_release_period: Time,
    ) {
        self.set_cliff_time(cliff_time);
        self.set_cliff_amount(cliff_amount);
        self.set_drip_period(drip_period);
        self.set_drip_amount(drip_amount);
        self.set_total_amount(total_amount);
        self.set_admin_release_period(admin_release_period);
        self.set_released_amount(Amount::zero());
        self.set_paused_flag(false);
        self.set_on_pause_period(Time::zero());
        self.set_last_pause_time(Time::zero());
    }

    fn available_amount(&self) -> Amount {
        let current_time = self.current_time();
        let cliff_time = self.get_cliff_time();
        if current_time < cliff_time + self.total_paused_time() {
            Amount::zero()
        } else {
            let total_amount = self.get_total_amount();
            let drip_period = self.get_drip_period();
            let drip_amount = self.get_drip_amount();
            let released_amount = self.get_released_amount();
            let mut available = self.get_cliff_amount();
            let time_diff: Time = current_time - cliff_time - self.total_paused_time();
            let available_drips = if drip_period == Time::zero() {
                Amount::zero()
            } else {
                time_diff / drip_period
            };
            available = available + drip_amount * available_drips - released_amount;
            available = cmp::min(available, total_amount);
            available
        }
    }

    fn withdraw(&mut self, amount: Amount) -> Result<(), VestingError> {
        let available_amount = self.available_amount();
        if available_amount < amount {
            Err(VestingError::NotEnoughBalance)
        } else {
            let released_amount = self.get_released_amount();
            self.set_released_amount(released_amount + amount);
            Ok(())
        }
    }

    fn pause(&mut self) -> Result<(), VestingError> {
        if !self.is_paused() {
            self.set_last_pause_time(self.current_time());
            self.set_paused_flag(true);
            Ok(())
        } else {
            Err(VestingError::AlreadyPaused)
        }
    }

    fn unpause(&mut self) -> Result<(), VestingError> {
        if self.is_paused() {
            let on_pause_period = self.get_on_pause_period();
            let last_pause_time = self.get_last_pause_time();
            let elapsed_time = self.current_time() - last_pause_time;
            self.set_on_pause_period(on_pause_period + elapsed_time);
            self.set_last_pause_time(Time::zero());
            self.set_paused_flag(false);
            Ok(())
        } else {
            Err(VestingError::AlreadyUnpaused)
        }
    }

    fn total_paused_time(&self) -> Time {
        let mut on_pause_period = self.get_on_pause_period();
        if self.is_paused() {
            let last_pause_time = self.get_last_pause_time();
            let since_last_pause = self.current_time() - last_pause_time;
            on_pause_period = on_pause_period + since_last_pause;
        }
        on_pause_period
    }

    fn is_paused(&self) -> bool {
        self.get_paused_flag()
    }

    fn admin_release(&mut self) -> Result<Amount, VestingError> {
        if !self.is_paused() {
            return Err(VestingError::AdminReleaseErrorNotPaused);
        }
        let last_pause_time = self.get_last_pause_time();
        let since_last_pause = self.current_time() - last_pause_time;
        let required_wait_period = self.get_admin_release_period();
        if since_last_pause < required_wait_period {
            return Err(VestingError::AdminReleaseErrorNotEnoughTimeElapsed);
        }
        let total_amount = self.get_total_amount();
        let released_amount = self.get_released_amount();
        if total_amount == released_amount {
            return Err(VestingError::AdminReleaseErrorNothingToWithdraw);
        }
        let amount_to_withdraw = total_amount - released_amount;
        self.set_released_amount(total_amount);
        Ok(amount_to_withdraw)
    }

    fn set_cliff_time(&mut self, cliff_time: Time) {
        self.set_time(key::CLIFF_TIME, cliff_time);
    }

    fn get_cliff_time(&self) -> Time {
        self.get_time(key::CLIFF_TIME)
    }

    fn set_cliff_amount(&mut self, drip_amount: Amount) {
        self.set_amount(key::CLIFF_AMOUNT, drip_amount);
    }

    fn get_cliff_amount(&self) -> Amount {
        self.get_amount(key::CLIFF_AMOUNT)
    }

    fn set_drip_period(&mut self, drip_period: Time) {
        self.set_time(key::DRIP_PERIOD, drip_period);
    }

    fn get_drip_period(&self) -> Time {
        self.get_time(key::DRIP_PERIOD)
    }

    fn set_drip_amount(&mut self, drip_amount: Amount) {
        self.set_amount(key::DRIP_AMOUNT, drip_amount);
    }

    fn get_drip_amount(&self) -> Amount {
        self.get_amount(key::DRIP_AMOUNT)
    }

    fn set_total_amount(&mut self, total_amount: Amount) {
        self.set_amount(key::TOTAL_AMOUNT, total_amount);
    }

    fn get_total_amount(&self) -> Amount {
        self.get_amount(key::TOTAL_AMOUNT)
    }

    fn set_released_amount(&mut self, released_amount: Amount) {
        self.set_amount(key::RELEASED_AMOUNT, released_amount);
    }

    fn get_released_amount(&self) -> Amount {
        self.get_amount(key::RELEASED_AMOUNT)
    }

    fn set_admin_release_period(&mut self, admin_release_period: Time) {
        self.set_time(key::ADMIN_RELEASE_PERIOD, admin_release_period);
    }

    fn get_admin_release_period(&self) -> Time {
        self.get_time(key::ADMIN_RELEASE_PERIOD)
    }

    fn set_on_pause_period(&mut self, on_pause_period: Time) {
        self.set_time(key::ON_PAUSE_PERIOD, on_pause_period);
    }

    fn get_on_pause_period(&self) -> Time {
        self.get_time(key::ON_PAUSE_PERIOD)
    }

    fn set_last_pause_time(&mut self, last_pause_time: Time) {
        self.set_time(key::LAST_PAUSE_TIME, last_pause_time);
    }

    fn get_last_pause_time(&self) -> Time {
        self.get_time(key::LAST_PAUSE_TIME)
    }

    fn set_paused_flag(&mut self, is_paused: bool) {
        self.set_boolean(key::PAUSE_FLAG, is_paused);
    }

    fn get_paused_flag(&self) -> bool {
        self.get_boolean(key::PAUSE_FLAG)
    }

    fn current_time(&self) -> Time;
    fn set_amount(&mut self, name: &str, value: Amount);
    fn get_amount(&self, name: &str) -> Amount;
    fn set_time(&mut self, name: &str, value: Time);
    fn get_time(&self, name: &str) -> Time;
    fn set_boolean(&mut self, name: &str, value: bool);
    fn get_boolean(&self, name: &str) -> bool;
}
