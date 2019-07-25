use alloc::collections::{BTreeMap, BTreeSet};
use alloc::string::String;
use core::fmt::Write;

use cl_std::contract_api;
use cl_std::key::Key;
use cl_std::value::{account::PublicKey, U512};

use crate::error::{Error, Result};

use super::{MAX_DECREASE, MAX_INCREASE, MAX_REL_DECREASE, MAX_REL_INCREASE, MAX_SPREAD};

pub trait StakesProvider {
    fn read() -> Result<Stakes>;
    fn write(stakes: &Stakes);
}

/// A `StakesProvider` that reads and writes the stakes to/from the contract's known urefs.
pub struct ContractStakes;

impl StakesProvider for ContractStakes {
    /// Reads the current stakes from the contract's known urefs.
    fn read() -> Result<Stakes> {
        let mut stakes = BTreeMap::new();
        for (name, _) in contract_api::list_known_urefs() {
            let mut split_name = name.split('_');
            if Some("v") != split_name.next() {
                continue;
            }
            let hex_key = split_name
                .next()
                .ok_or(Error::StakesKeyDeserializationFailed)?;
            let mut key_bytes = [0u8; 32];
            for i in 0..32 {
                key_bytes[i] = u8::from_str_radix(&hex_key[2 * i..2 * (i + 1)], 16)
                    .map_err(|_| Error::StakesKeyDeserializationFailed)?;
            }
            let pub_key = PublicKey::new(key_bytes);
            let balance = split_name
                .next()
                .and_then(|b| U512::from_dec_str(b).ok())
                .ok_or(Error::StakesDeserializationFailed)?;
            stakes.insert(pub_key, balance);
        }
        if stakes.is_empty() {
            return Err(Error::StakesNotFound);
        }
        Ok(Stakes(stakes))
    }

    /// Writes the current stakes to the contract's known urefs.
    fn write(stakes: &Stakes) {
        // Encode the stakes as a set of uref names.
        let mut new_urefs: BTreeSet<String> = stakes
            .0
            .iter()
            .map(|(pub_key, balance)| {
                let key_bytes = pub_key.value();
                let mut hex_key = String::with_capacity(64);
                for byte in &key_bytes[..32] {
                    write!(hex_key, "{:02x}", byte).expect("Writing to a string cannot fail");
                }
                let mut uref = String::new();
                uref.write_fmt(format_args!("v_{}_{}", hex_key, balance))
                    .expect("Writing to a string cannot fail");
                uref
            })
            .collect();
        // Remove and add urefs to update the contract's known urefs accordingly.
        for (name, _) in contract_api::list_known_urefs() {
            if name.starts_with("v_") && !new_urefs.remove(&name) {
                contract_api::remove_uref(&name);
            }
        }
        for name in new_urefs {
            contract_api::add_uref(&name, &Key::Hash([0; 32]));
        }
    }
}

/// The stakes map, assigning the staked amount of tokens to each bonded validator.
#[derive(Clone, Debug, PartialEq)]
pub struct Stakes(pub BTreeMap<PublicKey, U512>);

impl Stakes {
    /// If `maybe_amount` is `None`, removes all the validator's stakes, otherwise subtracts the
    /// given amount. If the stakes are lower than the specified amount, it also subtracts all the
    /// stakes.
    ///
    /// Returns the amount that was actually subtracted from the stakes, or an error if
    /// * unbonding the specified amount is not allowed,
    /// * tries to unbond last validator,
    /// * validator was not bonded.
    pub fn unbond(&mut self, validator: &PublicKey, maybe_amount: Option<U512>) -> Result<U512> {
        let min = self
            .max_without(validator)
            .unwrap_or_else(U512::zero)
            .saturating_sub(MAX_SPREAD);
        let max_decrease = MAX_DECREASE.min(self.sum() * MAX_REL_DECREASE / 1_000_000);

        if let Some(amount) = maybe_amount {
            // The minimum stake value to not violate the maximum spread.
            let stake = self.0.get_mut(validator).ok_or(Error::NotBonded)?;
            if *stake > amount {
                if *stake - amount < min {
                    return Err(Error::SpreadTooHigh);
                }
                if amount > max_decrease {
                    return Err(Error::UnbondTooLarge);
                }
                *stake -= amount;
                return Ok(amount);
            }
        }
        if self.0.len() == 1 {
            return Err(Error::CannotUnbondLastValidator);
        }
        // If the the amount is greater or equal to the stake, remove the validator.
        let stake = self.0.remove(validator).ok_or(Error::NotBonded)?;
        if stake > min.saturating_add(max_decrease) && stake > max_decrease {
            return Err(Error::UnbondTooLarge);
        }
        Ok(stake)
    }

    /// Adds `amount` to the validator's stakes.
    pub fn bond(&mut self, validator: &PublicKey, amount: U512) {
        self.0
            .entry(*validator)
            .and_modify(|x| *x += amount)
            .or_insert(amount);
    }

    /// Returns an error if bonding the specified amount is not allowed.
    pub fn validate_bonding(&self, validator: &PublicKey, amount: U512) -> Result<()> {
        let max = self
            .min_without(validator)
            .unwrap_or(U512::MAX)
            .saturating_add(MAX_SPREAD);
        let min = self
            .max_without(validator)
            .unwrap_or_else(U512::zero)
            .saturating_sub(MAX_SPREAD);
        let stake = self.0.get(validator).map(|s| *s + amount).unwrap_or(amount);
        if stake > max || stake < min {
            return Err(Error::SpreadTooHigh);
        }
        let max_increase = MAX_INCREASE.min(self.sum() * MAX_REL_INCREASE / 1_000_000);
        let zero = U512::zero();
        if (stake == zero && amount > min.saturating_add(max_increase))
            || (stake > zero && amount > max_increase)
        {
            return Err(Error::BondTooLarge);
        }
        Ok(())
    }

    /// Returns the minimum stake of the _other_ validators.
    fn min_without(&self, validator: &PublicKey) -> Option<U512> {
        self.0
            .iter()
            .filter(|(v, _)| *v != validator)
            .map(|(_, s)| s)
            .min()
            .cloned()
    }

    /// Returns the maximum stake of the _other_ validators.
    fn max_without(&self, validator: &PublicKey) -> Option<U512> {
        self.0
            .iter()
            .filter(|(v, _)| *v != validator)
            .map(|(_, s)| s)
            .max()
            .cloned()
    }

    /// Returns the total stakes.
    fn sum(&self) -> U512 {
        self.0
            .values()
            .fold(U512::zero(), |sum, s| sum.saturating_add(*s))
    }
}

#[cfg(test)]
mod tests {
    use cl_std::value::{account::PublicKey, U512};

    use crate::error::Error;
    use crate::stakes::Stakes;

    const KEY1: [u8; 32] = [1; 32];
    const KEY2: [u8; 32] = [2; 32];

    fn new_stakes(stakes: &[([u8; 32], u64)]) -> Stakes {
        Stakes(
            stakes
                .iter()
                .map(|&(key, amount)| (PublicKey::new(key), U512::from(amount)))
                .collect(),
        )
    }

    #[test]
    fn test_bond() {
        let mut stakes = new_stakes(&[(KEY2, 100)]);
        assert_eq!(
            Ok(()),
            stakes.validate_bonding(&PublicKey::new(KEY1), U512::from(5))
        );
        stakes.bond(&PublicKey::new(KEY1), U512::from(5));
        assert_eq!(new_stakes(&[(KEY1, 5), (KEY2, 100)]), stakes);
    }

    #[test]
    fn test_bond_existing() {
        let mut stakes = new_stakes(&[(KEY1, 50), (KEY2, 100)]);
        assert_eq!(
            Ok(()),
            stakes.validate_bonding(&PublicKey::new(KEY1), U512::from(4))
        );
        stakes.bond(&PublicKey::new(KEY1), U512::from(4));
        assert_eq!(new_stakes(&[(KEY1, 54), (KEY2, 100)]), stakes);
    }

    #[test]
    fn test_bond_too_much_rel() {
        let stakes = new_stakes(&[(KEY1, 1_000), (KEY2, 1_000)]);
        let total = 1_000 + 1_000;
        assert_eq!(
            Err(Error::BondTooLarge),
            stakes.validate_bonding(
                &PublicKey::new(KEY1),
                U512::from(crate::MAX_REL_INCREASE * total / 1_000_000 + 1)
            ),
            "Successfully bonded more than the maximum amount."
        );
        assert_eq!(
            Ok(()),
            stakes.validate_bonding(
                &PublicKey::new(KEY1),
                U512::from(crate::MAX_REL_INCREASE * total / 1_000_000)
            ),
            "Failed to bond the maximum amount."
        );
    }

    #[test]
    fn test_unbond() {
        let mut stakes = new_stakes(&[(KEY1, 5), (KEY2, 100)]);
        assert_eq!(
            Ok(U512::from(5)),
            stakes.unbond(&PublicKey::new(KEY1), None)
        );
        assert_eq!(new_stakes(&[(KEY2, 100)]), stakes);
    }

    #[test]
    fn test_unbond_last_validator() {
        let mut stakes = new_stakes(&[(KEY1, 5)]);
        assert_eq!(
            Err(Error::CannotUnbondLastValidator),
            stakes.unbond(&PublicKey::new(KEY1), None)
        );
    }

    #[test]
    fn test_partially_unbond() {
        let mut stakes = new_stakes(&[(KEY1, 50)]);
        assert_eq!(
            Ok(U512::from(4)),
            stakes.unbond(&PublicKey::new(KEY1), Some(U512::from(4)))
        );
        assert_eq!(new_stakes(&[(KEY1, 46)]), stakes);
    }

    #[test]
    fn test_unbond_too_much_rel() {
        let mut stakes = new_stakes(&[(KEY1, 999), (KEY2, 1)]);
        let total = 999 + 1;
        assert_eq!(
            Err(Error::UnbondTooLarge),
            stakes.unbond(
                &PublicKey::new(KEY1),
                Some(U512::from(crate::MAX_REL_DECREASE * total / 1_000_000 + 1))
            ),
            "Successfully unbonded more than the maximum amount."
        );
        assert_eq!(
            Ok(U512::from(crate::MAX_REL_DECREASE * total / 1_000_000)),
            stakes.unbond(
                &PublicKey::new(KEY1),
                Some(U512::from(crate::MAX_REL_DECREASE * total / 1_000_000))
            ),
            "Failed to unbond the maximum amount."
        );
    }
}
