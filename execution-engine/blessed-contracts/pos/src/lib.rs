#![no_std]
#![feature(alloc)]

extern crate alloc;

mod error;
mod queue;
mod stakes;

use alloc::string::String;
use alloc::vec::Vec;

use cl_std::contract_api;
use cl_std::uref::URef;
use cl_std::value::account::{BlockTime, PublicKey, PurseId};
use cl_std::value::U512;

use crate::error::Error;
use crate::queue::{QueueEntry, QueueLocal, QueueProvider};
use crate::stakes::{ContractStakes, StakesProvider};

/// The time from a bonding request until the bond becomes effective and part of the stake.
const BOND_DELAY: u64 = 0;
/// The time from an unbonding request until the stakes are paid out.
const UNBOND_DELAY: u64 = 0;
/// The maximum number of pending bonding requests.
const MAX_BOND_LEN: usize = 10;
/// The maximum number of pending unbonding requests.
const MAX_UNBOND_LEN: usize = 100;
/// The maximum difference between the largest and the smallest stakes.
// TODO: Should this be a percentage instead?
// TODO: Pick a reasonable value.
const MAX_SPREAD: U512 = U512::MAX;
/// The maximum increase of stakes in a single bonding request.
const MAX_INCREASE: U512 = U512::MAX;
/// The maximum decrease of stakes in a single unbonding request.
const MAX_DECREASE: U512 = U512::MAX;
/// The maximum increase of stakes in millionths of the total stakes in a single bonding request.
const MAX_REL_INCREASE: u64 = 500_000;
/// The maximum decrease of stakes in millionths of the total stakes in a single unbonding request.
const MAX_REL_DECREASE: u64 = 100_000;

/// Enqueues the deploy's creator for becoming a validator. The bond `amount` is paid from the
/// purse `source`.
fn bond<Q: QueueProvider, S: StakesProvider>(
    amount: U512,
    validator: PublicKey,
    timestamp: BlockTime,
) -> Result<(), Error> {
    let mut queue = Q::read_bonding();
    if queue.0.len() >= MAX_BOND_LEN {
        return Err(Error::TooManyEventsInQueue);
    }

    let mut stakes = S::read().ok_or(Error::InvalidContractState)?;
    // Simulate applying all earlier bonds. The modified stakes are not written.
    for entry in &queue.0 {
        stakes.bond(&entry.validator, &entry.amount);
    }
    stakes.validate_bonding(&validator, &amount)?;

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
) -> Result<(), Error> {
    let mut queue = Q::read_unbonding();
    if queue.0.len() >= MAX_UNBOND_LEN {
        return Err(Error::TooManyEventsInQueue);
    }

    let mut stakes = S::read().ok_or(Error::InvalidContractState)?;
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
fn step<Q: QueueProvider, S: StakesProvider>(timestamp: BlockTime) -> Vec<QueueEntry> {
    let mut bonding_queue = Q::read_bonding();
    let mut unbonding_queue = Q::read_unbonding();

    let bonds = bonding_queue.pop_due(BlockTime(timestamp.0.saturating_sub(BOND_DELAY)));
    let unbonds = unbonding_queue.pop_due(BlockTime(timestamp.0.saturating_sub(UNBOND_DELAY)));

    if !unbonds.is_empty() {
        Q::write_unbonding(&unbonding_queue);
    }

    if !bonds.is_empty() {
        Q::write_bonding(&bonding_queue);
        let mut stakes = S::read().unwrap();
        for entry in bonds {
            stakes.bond(&entry.validator, &entry.amount);
        }
        S::write(&stakes);
    }

    unbonds
}

#[no_mangle]
pub extern "C" fn pos_ext() {
    let method_name: String = contract_api::get_arg(0);
    let timestamp = contract_api::get_blocktime();
    let validator = contract_api::get_caller().unwrap(); // TODO: Error handling.

    match method_name.as_str() {
        // Type of this method: `fn bond(amount: U512, purse: URef)`
        "bond" => {
            let amount = contract_api::get_arg(1);
            let source_uref: URef = contract_api::get_arg(2);
            let _source = PurseId::new(source_uref);
            // Transfer `amount` from the `source` purse to PoS internal purse.
            // POS_PURSE is a constant, it is the PurseID of the proof-of-stake contract's own purse.
            // Mateusz: moved this outside of `bond` function so that it [bond] can be unit tested.
            // contract_api::transfer(source, POS_PURSE, amount)?;
            bond::<QueueLocal, ContractStakes>(amount, validator, timestamp)
                .expect("Failed to bond");

            // TODO: Remove this and set nonzero delays once the system calls `step` in each block.
            let unbonds = step::<QueueLocal, ContractStakes>(timestamp);
            for _entry in unbonds {
                // contract_api::transfer(POS_PURSE, entry.validator, entry.amount)?;
            }
        }
        // Type of this method: `fn unbond(amount: U512)`
        "unbond" => {
            let maybe_amount = contract_api::get_arg(1);
            unbond::<QueueLocal, ContractStakes>(maybe_amount, validator, timestamp)
                .expect("Failed to unbond");

            // TODO: Remove this and set nonzero delays once the system calls `step` in each block.
            let unbonds = step::<QueueLocal, ContractStakes>(timestamp);
            for _entry in unbonds {
                // contract_api::transfer(POS_PURSE, entry.validator, entry.amount)?;
            }
        }
        // Type of this method: `fn step()`
        "step" => {
            // This is called by the system in every block.
            let unbonds = step::<QueueLocal, ContractStakes>(timestamp);

            // Mateusz: Moved outside of `step` function so that it [step] can be unit tested.
            for _entry in unbonds {
                // contract_api::transfer(POS_PURSE, entry.validator, entry.amount)?;
            }
        }
        _ => {}
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let _hash = contract_api::store_function("pos_ext", Default::default());
}
