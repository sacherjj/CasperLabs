#![cfg_attr(not(test), no_std)]
#![feature(alloc)]

#[macro_use]
extern crate alloc;

mod error;
mod queue;
mod stakes;

use alloc::string::String;
use alloc::vec::Vec;

use cl_std::contract_api;
use cl_std::key::Key;
use cl_std::uref::{AccessRights, URef};
use cl_std::value::account::{BlockTime, PublicKey, PurseId};
use cl_std::value::U512;

use crate::error::{Error, PurseLookupError, Result, ResultExt};
use crate::queue::{QueueEntry, QueueLocal, QueueProvider};
use crate::stakes::{ContractStakes, StakesProvider};

/// The uref name where the PoS purse is stored. It contains all staked tokens, and all unbonded
/// tokens that are yet to be paid out.
const BONDING_PURSE_KEY: &str = "pos_bonding_purse";

/// The uref name where the PoS accepts payment for computation on behalf of validators.
const PAYMENT_PURSE_KEY: &str = "pos_payment_purse";

/// The uref name where the PoS will refund unused payment back to the user. The uref
/// this name corresponds to is set by the user.
const REFUND_PURSE_KEY: &str = "pos_refund_purse";

/// The time from a bonding request until the bond becomes effective and part of the stake.
const BOND_DELAY: u64 = 0;
/// The time from an unbonding request until the stakes are paid out.
const UNBOND_DELAY: u64 = 0;
/// The maximum number of pending bonding requests.
const MAX_BOND_LEN: usize = 100;
/// The maximum number of pending unbonding requests.
const MAX_UNBOND_LEN: usize = 1000;
/// The maximum difference between the largest and the smallest stakes.
// TODO: Should this be a percentage instead?
// TODO: Pick a reasonable value.
const MAX_SPREAD: U512 = U512::MAX;
/// The maximum increase of stakes in a single bonding request.
const MAX_INCREASE: U512 = U512::MAX;
/// The maximum decrease of stakes in a single unbonding request.
const MAX_DECREASE: U512 = U512::MAX;
/// The maximum increase of stakes in millionths of the total stakes in a single bonding request.
const MAX_REL_INCREASE: u64 = 1_000_000_000;
/// The maximum decrease of stakes in millionths of the total stakes in a single unbonding request.
const MAX_REL_DECREASE: u64 = 900_000;

/// Enqueues the deploy's creator for becoming a validator. The bond `amount` is paid from the
/// purse `source`.
fn bond<Q: QueueProvider, S: StakesProvider>(
    amount: U512,
    validator: PublicKey,
    timestamp: BlockTime,
) -> Result<()> {
    let mut queue = Q::read_bonding();
    if queue.0.len() >= MAX_BOND_LEN {
        return Err(Error::TooManyEventsInQueue);
    }

    let mut stakes = S::read()?;
    // Simulate applying all earlier bonds. The modified stakes are not written.
    for entry in &queue.0 {
        stakes.bond(&entry.validator, entry.amount);
    }
    stakes.validate_bonding(&validator, amount)?;

    queue.push(validator, amount, timestamp)?;
    Q::write_bonding(&queue);
    Ok(())
}

/// Enqueues the deploy's creator for unbonding. Their vote weight as a validator is decreased
/// immediately, but the funds will only be released after a delay. If `maybe_amount` is `None`,
/// all funds are enqueued for withdrawal, terminating the validator status.
fn unbond<Q: QueueProvider, S: StakesProvider>(
    maybe_amount: Option<U512>,
    validator: PublicKey,
    timestamp: BlockTime,
) -> Result<()> {
    let mut queue = Q::read_unbonding();
    if queue.0.len() >= MAX_UNBOND_LEN {
        return Err(Error::TooManyEventsInQueue);
    }

    let mut stakes = S::read()?;
    let payout = stakes.unbond(&validator, maybe_amount)?;
    S::write(&stakes);
    // TODO: Make sure the destination is valid and the amount can be paid. The actual payment will
    // be made later, after the unbonding delay.
    // contract_api::transfer_dry_run(POS_PURSE, dest, amount)?;
    queue.push(validator, payout, timestamp)?;
    Q::write_unbonding(&queue);
    Ok(())
}

/// Removes all due requests from the queues and applies them.
fn step<Q: QueueProvider, S: StakesProvider>(timestamp: BlockTime) -> Result<Vec<QueueEntry>> {
    let mut bonding_queue = Q::read_bonding();
    let mut unbonding_queue = Q::read_unbonding();

    let bonds = bonding_queue.pop_due(BlockTime(timestamp.0.saturating_sub(BOND_DELAY)));
    let unbonds = unbonding_queue.pop_due(BlockTime(timestamp.0.saturating_sub(UNBOND_DELAY)));

    if !unbonds.is_empty() {
        Q::write_unbonding(&unbonding_queue);
    }

    if !bonds.is_empty() {
        Q::write_bonding(&bonding_queue);
        let mut stakes = S::read()?;
        for entry in bonds {
            stakes.bond(&entry.validator, entry.amount);
        }
        S::write(&stakes);
    }

    Ok(unbonds)
}

/// Attempts to look up a purse from the known_urefs.
fn get_purse_id(name: &str) -> core::result::Result<PurseId, PurseLookupError> {
    contract_api::get_uref(name)
        .ok_or(PurseLookupError::KeyNotFound)
        .and_then(|key| match key {
            Key::URef(uref) => Ok(PurseId::new(uref)),
            _ => Err(PurseLookupError::KeyUnexpectedType),
        })
}

/// Returns the purse for accepting payment for tranasactions.
fn get_payment_purse() -> Result<PurseId> {
    get_purse_id(PAYMENT_PURSE_KEY).map_err(PurseLookupError::payment)
}

/// Returns the purse for holding bonds
fn get_bonding_purse() -> Result<PurseId> {
    get_purse_id(BONDING_PURSE_KEY).map_err(PurseLookupError::bonding)
}

fn set_refund(purse_id: URef) {
    contract_api::add_uref(REFUND_PURSE_KEY, &Key::URef(purse_id));
}

fn get_refund_purse() -> Option<PurseId> {
    match get_purse_id(REFUND_PURSE_KEY) {
        Ok(purse_id) => Some(purse_id),
        Err(PurseLookupError::KeyNotFound) => None,
        Err(PurseLookupError::KeyUnexpectedType) => {
            contract_api::revert(Error::RefundPurseKeyUnexpectedType.into())
        }
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let method_name: String = contract_api::get_arg(0);
    let timestamp = contract_api::get_blocktime();
    let pos_purse = get_bonding_purse().unwrap_or_revert();

    match method_name.as_str() {
        // Type of this method: `fn bond(amount: U512, purse: URef)`
        "bond" => {
            let validator = contract_api::get_caller();
            let amount = contract_api::get_arg(1);
            let source_uref: URef = contract_api::get_arg(2);
            let source = PurseId::new(source_uref);
            // Transfer `amount` from the `source` purse to PoS internal purse.
            // POS_PURSE is a constant, it is the PurseID of the proof-of-stake contract's own purse.
            if contract_api::PurseTransferResult::TransferError
                == contract_api::transfer_from_purse_to_purse(source, pos_purse, amount)
            {
                contract_api::revert(Error::BondTransferFailed.into());
            }
            bond::<QueueLocal, ContractStakes>(amount, validator, timestamp).unwrap_or_revert();

            // TODO: Remove this and set nonzero delays once the system calls `step` in each block.
            let unbonds = step::<QueueLocal, ContractStakes>(timestamp).unwrap_or_revert();
            for entry in unbonds {
                contract_api::transfer_from_purse_to_account(
                    pos_purse,
                    entry.validator,
                    entry.amount,
                );
            }
        }
        // Type of this method: `fn unbond(amount: Option<U512>)`
        "unbond" => {
            let validator = contract_api::get_caller();
            let maybe_amount = contract_api::get_arg(1);
            unbond::<QueueLocal, ContractStakes>(maybe_amount, validator, timestamp)
                .unwrap_or_revert();

            // TODO: Remove this and set nonzero delays once the system calls `step` in each block.
            let unbonds = step::<QueueLocal, ContractStakes>(timestamp).unwrap_or_revert();
            for entry in unbonds {
                if contract_api::TransferResult::TransferError
                    == contract_api::transfer_from_purse_to_account(
                        pos_purse,
                        entry.validator,
                        entry.amount,
                    )
                {
                    contract_api::revert(Error::UnbondTransferFailed.into());
                }
            }
        }
        // Type of this method: `fn step()`
        "step" => {
            // This is called by the system in every block.
            let unbonds = step::<QueueLocal, ContractStakes>(timestamp).unwrap_or_revert();

            // Mateusz: Moved outside of `step` function so that it [step] can be unit tested.
            for entry in unbonds {
                // TODO: We currently ignore `TransferResult::TransferError`s here, since we can't
                // recover from them and we shouldn't retry indefinitely. That would mean the
                // contract just keeps the money forever, though.
                contract_api::transfer_from_purse_to_account(
                    pos_purse,
                    entry.validator,
                    entry.amount,
                );
            }
        }
        "get_payment_purse" => {
            let purse = get_payment_purse().unwrap_or_revert();
            // Limit the access rights so only balance query and deposit are allowed.
            let rights_controlled_purse =
                PurseId::new(URef::new(purse.value().addr(), AccessRights::READ_ADD));
            contract_api::ret(
                &rights_controlled_purse,
                &vec![rights_controlled_purse.value()],
            );
        }
        "set_refund_purse" => {
            let purse_id: PurseId = contract_api::get_arg(1);
            set_refund(purse_id.value());
        }
        "get_refund_purse" => {
            // We purposely choose to remove the access rights so that we do not
            // accidentally give rights for a purse to some contract that is not
            // supposed to have it.
            let result = get_refund_purse().map(|p| p.value().remove_access_rights());
            if let Some(uref) = result {
                contract_api::ret(&Some(PurseId::new(uref)), &vec![uref]);
            } else {
                contract_api::ret(&result, &Vec::new());
            }
        }
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use std::cell::RefCell;
    use std::iter;

    use cl_std::value::{
        account::{BlockTime, PublicKey},
        U512,
    };

    use crate::error::Result;
    use crate::queue::{Queue, QueueProvider};
    use crate::stakes::{Stakes, StakesProvider};
    use crate::{bond, step, unbond, BOND_DELAY, UNBOND_DELAY};

    const KEY1: [u8; 32] = [1; 32];
    const KEY2: [u8; 32] = [2; 32];

    thread_local! {
        static BONDING: RefCell<Queue> = RefCell::new(Queue(Default::default()));
        static UNBONDING: RefCell<Queue> = RefCell::new(Queue(Default::default()));
        static STAKES: RefCell<Stakes> = RefCell::new(
            Stakes(iter::once((PublicKey::new(KEY1), U512::from(1_000))).collect())
        );
    }

    struct TestQueues;

    impl QueueProvider for TestQueues {
        fn read_bonding() -> Queue {
            BONDING.with(|b| b.borrow().clone())
        }

        fn read_unbonding() -> Queue {
            UNBONDING.with(|ub| ub.borrow().clone())
        }

        fn write_bonding(queue: &Queue) {
            BONDING.with(|b| b.replace(queue.clone()));
        }

        fn write_unbonding(queue: &Queue) {
            UNBONDING.with(|ub| ub.replace(queue.clone()));
        }
    }

    struct TestStakes;

    impl StakesProvider for TestStakes {
        fn read() -> Result<Stakes> {
            STAKES.with(|s| Ok(s.borrow().clone()))
        }

        fn write(stakes: &Stakes) {
            STAKES.with(|s| s.replace(stakes.clone()));
        }
    }

    fn assert_stakes(stakes: &[([u8; 32], usize)]) {
        let expected = Stakes(
            stakes
                .iter()
                .map(|(key, amount)| (PublicKey::new(*key), U512::from(*amount)))
                .collect(),
        );
        assert_eq!(Ok(expected), TestStakes::read());
    }

    #[test]
    fn test_bond_step_unbond() {
        bond::<TestQueues, TestStakes>(U512::from(500), PublicKey::new(KEY2), BlockTime(1))
            .expect("bond validator 2");

        // Bonding becomes effective only after the delay.
        assert_stakes(&[(KEY1, 1_000)]);
        step::<TestQueues, TestStakes>(BlockTime(BOND_DELAY)).expect("step 1");
        assert_stakes(&[(KEY1, 1_000)]);
        step::<TestQueues, TestStakes>(BlockTime(1 + BOND_DELAY)).expect("step 2");
        assert_stakes(&[(KEY1, 1_000), (KEY2, 500)]);

        unbond::<TestQueues, TestStakes>(Some(U512::from(500)), PublicKey::new(KEY1), BlockTime(2))
            .expect("partly unbond validator 1");

        // Unbonding becomes effective immediately.
        assert_stakes(&[(KEY1, 500), (KEY2, 500)]);
        step::<TestQueues, TestStakes>(BlockTime(2 + UNBOND_DELAY)).expect("step 3");
        assert_stakes(&[(KEY1, 500), (KEY2, 500)]);
    }
}
