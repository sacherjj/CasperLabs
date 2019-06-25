use alloc::collections::{BTreeMap, BTreeSet};
use alloc::string::String;
use core::fmt::Write;

use cl_std::contract_api;
use cl_std::key::Key;
use cl_std::value::{account::PublicKey, U512};

use crate::error::Error;

use super::{MAX_DECREASE, MAX_INCREASE, MAX_REL_DECREASE, MAX_REL_INCREASE, MAX_SPREAD};

pub trait StakesProvider {
    fn read() -> Option<Stakes>;
    fn write(stakes: &Stakes);
}

pub struct ContractStakes;

impl StakesProvider for ContractStakes {
    /// Reads the current stakes from the contract's known urefs.
    fn read() -> Option<Stakes> {
        let mut stakes = BTreeMap::new();
        for (name, _) in contract_api::list_known_urefs() {
            let mut split_name = name.split('_');
            if Some("v") != split_name.next() {
                continue;
            }
            let hex_key = split_name.next()?;
            let mut key_bytes = [0u8; 32];
            for i in 0..32 {
                key_bytes[i] = u8::from_str_radix(&hex_key[2 * i..2 * (i + 1)], 16).ok()?;
            }
            let pub_key = PublicKey::new(key_bytes);
            let balance_dec = split_name.next()?;
            let balance = U512::from_dec_str(balance_dec).ok()?;
            stakes.insert(pub_key, balance);
        }
        Some(Stakes(stakes))
    }

    /// Writes the current stakes to the contract's known urefs.
    // TODO: Error handling
    fn write(stakes: &Stakes) {
        // Encode the stakes as a set of uref names.
        let mut new_urefs: BTreeSet<String> = stakes
            .0
            .iter()
            .map(|(pub_key, balance)| {
                let key_bytes = pub_key.value();
                let mut hex_key = String::with_capacity(64);
                for byte in &key_bytes[..32] {
                    write!(hex_key, "{:02X}", byte).unwrap();
                }
                let mut uref = String::new();
                uref.write_fmt(format_args!("v_{}_{}", hex_key, balance))
                    .unwrap();
                uref
            })
            .collect();
        // Remove and add urefs to update the contract's known urefs accordingly.
        for (name, _) in contract_api::list_known_urefs() {
            // Mateusz: Why are we removing `name` from the `new_urefs` set?
            if name.starts_with("v_") && !new_urefs.remove(&name) {
                contract_api::remove_uref(&name);
            }
        }
        for name in new_urefs {
            contract_api::add_uref(&name, &Key::Hash([0; 32]));
        }
    }
}

pub struct Stakes(pub BTreeMap<PublicKey, U512>);

impl Stakes {
    /// Removes `amount` from the validator's stakes. If they have been slashed, it can happen that
    /// they don't have enough stakes anymore. The actual number is returned.
    ///
    /// Returns an error if
    /// * unbonding the specified amount is not allowed,
    /// * tries to unbond last validator,
    /// * validator was not bonded.
    pub fn unbond(
        &mut self,
        validator: &PublicKey,
        maybe_amount: Option<U512>,
    ) -> Result<U512, Error> {
        let min = self.max_without(validator).saturating_sub(MAX_SPREAD);
        if self.0.len() == 1 {
            return Err(Error::CannotUnbondLastValidator);
        }

        if let Some(amount) = maybe_amount {
            // The minimum stake value to not violate the maximum spread.
            let stake = self.0.get_mut(validator).ok_or(Error::NotBonded)?;
            if *stake > amount {
                if *stake - amount < min {
                    return Err(Error::SpreadTooHigh);
                }
                *stake -= amount;
                return Ok(amount);
            }
        }
        // If the the amount is less or equal to the stake, remove the validator.
        let stake = self.0.remove(validator).ok_or(Error::NotBonded)?;
        let max_decrease = MAX_DECREASE.min(self.sum() * MAX_REL_DECREASE);
        if stake > min.saturating_add(MAX_DECREASE) && stake > max_decrease {
            return Err(Error::UnbondTooLarge);
        }
        Ok(stake)
    }

    /// Adds `amount` to the validator's stakes.
    pub fn bond(&mut self, validator: &PublicKey, amount: &U512) {
        self.0
            .entry(*validator)
            .and_modify(|x| *x += *amount)
            .or_insert(*amount);
    }

    // Mateusz: Why is there `validate_bonding` but there's no `validate_unbonding`?
    // `bond` always succeeds (I guess because it's called only in case `validate_bonding` succeeds,
    // but `unbond` can return an error. Maybe we should consider being consistent here.
    /// Returns an error if bonding the specified amount is not allowed.
    pub fn validate_bonding(&self, validator: &PublicKey, amount: &U512) -> Result<(), Error> {
        let max = self.min_without(validator).saturating_add(MAX_SPREAD);
        let min = self.max_without(validator).saturating_sub(MAX_SPREAD);
        let stake = self.0.get(validator).map(|s| s + amount).unwrap_or(*amount);
        if stake > max || stake < min {
            return Err(Error::SpreadTooHigh);
        }
        let max_increase = MAX_INCREASE.min(self.sum() * MAX_REL_INCREASE);
        if stake > min.saturating_add(MAX_INCREASE) && *amount > max_increase {
            return Err(Error::BondTooLarge);
        }
        Ok(())
    }

    /// Returns the minimum stake of the _other_ validators.
    fn min_without(&self, validator: &PublicKey) -> U512 {
        self.0
            .iter()
            .filter(|(v, _)| *v != validator)
            .map(|(_, s)| s)
            .min()
            .cloned()
            .unwrap_or(U512::MAX)
    }

    /// Returns the maximum stake of the _other_ validators.
    fn max_without(&self, validator: &PublicKey) -> U512 {
        self.0
            .iter()
            .filter(|(v, _)| *v != validator)
            .map(|(_, s)| s)
            .max()
            .cloned()
            .unwrap_or_else(U512::zero) // Mateusz: This is a bit unclear to me,
                                        // if `validator` is the only one in the set,
                                        // this function returns U512::zero.
                                        // I understand that this may be useful for code that uses
                                        // this returned value but maybe it would be better
                                        // to return `Option<U512>` and to the special handling on the call side?
    }

    /// Returns the total stakes.
    fn sum(&self) -> U512 {
        self.0
            .values()
            .fold(U512::zero(), |sum, s| sum.saturating_add(*s))
    }
}
