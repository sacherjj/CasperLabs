#![cfg_attr(not(test), no_std)]

extern crate alloc;

mod queue;
mod stakes;

use alloc::{string::String, vec::Vec};

use crate::{
    queue::{QueueEntry, QueueLocal, QueueProvider},
    stakes::{ContractStakes, StakesProvider},
};
use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    account::{PublicKey, PurseId},
    system_contract_errors::pos::{Error, PurseLookupError, Result},
    AccessRights, ApiError, BlockTime, CLValue, Key, Phase, URef, U512,
};

/// Account used to run system functions (in particular `finalize_payment`).
const SYSTEM_ACCOUNT: [u8; 32] = [0u8; 32];

/// The uref name where the PoS purse is stored. It contains all staked motes,
/// and all unbonded motes that are yet to be paid out.
const BONDING_PURSE_KEY: &str = "pos_bonding_purse";

/// The uref name where the PoS accepts payment for computation on behalf of
/// validators.
const PAYMENT_PURSE_KEY: &str = "pos_payment_purse";

/// The uref name where the PoS holds validator earnings before distributing
/// them.
const REWARDS_PURSE_KEY: &str = "pos_rewards_purse";

/// The uref name where the PoS will refund unused payment back to the user. The
/// uref this name corresponds to is set by the user.
const REFUND_PURSE_KEY: &str = "pos_refund_purse";

/// The time from a bonding request until the bond becomes effective and part of
/// the stake.
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
/// The maximum increase of stakes in millionths of the total stakes in a single
/// bonding request.
const MAX_REL_INCREASE: u64 = 1_000_000_000;
/// The maximum decrease of stakes in millionths of the total stakes in a single
/// unbonding request.
const MAX_REL_DECREASE: u64 = 900_000;

/// Enqueues the deploy's creator for becoming a validator. The bond `amount` is
/// paid from the purse `source`.
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
    Q::write_bonding(queue);
    Ok(())
}

/// Enqueues the deploy's creator for unbonding. Their vote weight as a
/// validator is decreased immediately, but the funds will only be released
/// after a delay. If `maybe_amount` is `None`, all funds are enqueued for
/// withdrawal, terminating the validator status.
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
    // TODO: Make sure the destination is valid and the amount can be paid. The
    // actual payment will be made later, after the unbonding delay.
    // contract_api::transfer_dry_run(POS_PURSE, dest, amount)?;
    queue.push(validator, payout, timestamp)?;
    Q::write_unbonding(queue);
    Ok(())
}

/// Removes all due requests from the queues and applies them.
fn step<Q: QueueProvider, S: StakesProvider>(timestamp: BlockTime) -> Result<Vec<QueueEntry>> {
    let mut bonding_queue = Q::read_bonding();
    let mut unbonding_queue = Q::read_unbonding();

    let bonds = bonding_queue.pop_due(timestamp.saturating_sub(BlockTime::new(BOND_DELAY)));
    let unbonds = unbonding_queue.pop_due(timestamp.saturating_sub(BlockTime::new(UNBOND_DELAY)));

    if !unbonds.is_empty() {
        Q::write_unbonding(unbonding_queue);
    }

    if !bonds.is_empty() {
        Q::write_bonding(bonding_queue);
        let mut stakes = S::read()?;
        for entry in bonds {
            stakes.bond(&entry.validator, entry.amount);
        }
        S::write(&stakes);
    }

    Ok(unbonds)
}

/// Attempts to look up a purse from the named_keys
fn get_purse_id(name: &str) -> core::result::Result<PurseId, PurseLookupError> {
    runtime::get_key(name)
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

/// Returns the purse for holding validator earnings
fn get_rewards_purse() -> Result<PurseId> {
    get_purse_id(REWARDS_PURSE_KEY).map_err(PurseLookupError::rewards)
}

/// Sets the purse where refunds (excess funds not spent to pay for computation)
/// will be sent. Note that if this function is never called, the default
/// location is the main purse of the deployer's account.
fn set_refund(purse_id: URef) {
    if let Phase::Payment = runtime::get_phase() {
        runtime::put_key(REFUND_PURSE_KEY, Key::URef(purse_id));
    } else {
        runtime::revert(Error::SetRefundPurseCalledOutsidePayment)
    }
}

/// Returns the currently set refund purse.
fn get_refund_purse() -> Option<PurseId> {
    match get_purse_id(REFUND_PURSE_KEY) {
        Ok(purse_id) => Some(purse_id),
        Err(PurseLookupError::KeyNotFound) => None,
        Err(PurseLookupError::KeyUnexpectedType) => {
            runtime::revert(Error::RefundPurseKeyUnexpectedType)
        }
    }
}

/// Transfers funds from the payment purse to the validator rewards
/// purse, as well as to the refund purse, depending on how much
/// was spent on the computation. This function maintains the invariant
/// that the balance of the payment purse is zero at the beginning and
/// end of each deploy and that the refund purse is unset at the beginning
/// and end of each deploy.
fn finalize_payment(amount_spent: U512, account: PublicKey) {
    let caller = runtime::get_caller();
    if caller.value() != SYSTEM_ACCOUNT {
        runtime::revert(Error::SystemFunctionCalledByUserAccount);
    }

    let payment_purse = get_payment_purse().unwrap_or_revert();
    let total = system::get_balance(payment_purse)
        .unwrap_or_revert_with(Error::PaymentPurseBalanceNotFound);
    if total < amount_spent {
        runtime::revert(Error::InsufficientPaymentForAmountSpent);
    }
    let refund_amount = total - amount_spent;

    let rewards_purse = get_rewards_purse().unwrap_or_revert();
    let refund_purse = get_refund_purse();
    runtime::remove_key(REFUND_PURSE_KEY); //unset refund purse after reading it

    // pay validators
    system::transfer_from_purse_to_purse(payment_purse, rewards_purse, amount_spent)
        .unwrap_or_revert_with(Error::FailedTransferToRewardsPurse);

    // give refund
    if !refund_amount.is_zero() {
        if let Some(purse) = refund_purse {
            if system::transfer_from_purse_to_purse(payment_purse, purse, refund_amount).is_err() {
                // on case of failure to transfer to refund purse we fall back on the account's
                // main purse
                refund_to_account(payment_purse, account, refund_amount)
            }
        } else {
            refund_to_account(payment_purse, account, refund_amount);
        }
    }
}

fn refund_to_account(payment_purse: PurseId, account: PublicKey, amount: U512) {
    system::transfer_from_purse_to_account(payment_purse, account, amount)
        .unwrap_or_revert_with(Error::FailedTransferToAccountPurse);
}

pub fn delegate() {
    let method_name: String = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let timestamp = runtime::get_blocktime();
    let pos_purse = get_bonding_purse().unwrap_or_revert();

    match method_name.as_str() {
        // Type of this method: `fn bond(amount: U512, purse: URef)`
        "bond" => {
            let validator = runtime::get_caller();
            let amount: U512 = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            if amount.is_zero() {
                runtime::revert(Error::BondTooSmall);
            }
            let source_uref: URef = runtime::get_arg(2)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let source = PurseId::new(source_uref);
            // Transfer `amount` from the `source` purse to PoS internal purse.
            // POS_PURSE is a constant, it is the PurseID of the proof-of-stake contract's
            // own purse.
            system::transfer_from_purse_to_purse(source, pos_purse, amount)
                .unwrap_or_revert_with(Error::BondTransferFailed);
            bond::<QueueLocal, ContractStakes>(amount, validator, timestamp).unwrap_or_revert();

            // TODO: Remove this and set nonzero delays once the system calls `step` in each
            // block.
            let unbonds = step::<QueueLocal, ContractStakes>(timestamp).unwrap_or_revert();
            for entry in unbonds {
                let _ = system::transfer_from_purse_to_account(
                    pos_purse,
                    entry.validator,
                    entry.amount,
                );
            }
        }
        // Type of this method: `fn unbond(amount: Option<U512>)`
        "unbond" => {
            let validator = runtime::get_caller();
            let maybe_amount = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            unbond::<QueueLocal, ContractStakes>(maybe_amount, validator, timestamp)
                .unwrap_or_revert();

            // TODO: Remove this and set nonzero delays once the system calls `step` in each
            // block.
            let unbonds = step::<QueueLocal, ContractStakes>(timestamp).unwrap_or_revert();
            for entry in unbonds {
                system::transfer_from_purse_to_account(pos_purse, entry.validator, entry.amount)
                    .unwrap_or_revert_with(Error::UnbondTransferFailed);
            }
        }
        // Type of this method: `fn step()`
        "step" => {
            // This is called by the system in every block.
            let unbonds = step::<QueueLocal, ContractStakes>(timestamp).unwrap_or_revert();

            // Mateusz: Moved outside of `step` function so that it [step] can be unit
            // tested.
            for entry in unbonds {
                // TODO: We currently ignore `TransferResult::TransferError`s here, since we
                // can't recover from them and we shouldn't retry indefinitely.
                // That would mean the contract just keeps the money forever,
                // though.
                let _ = system::transfer_from_purse_to_account(
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
            let return_value = CLValue::from_t(rights_controlled_purse).unwrap_or_revert();
            runtime::ret(return_value);
        }
        "set_refund_purse" => {
            let purse_id: PurseId = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            set_refund(purse_id.value());
        }
        "get_refund_purse" => {
            // We purposely choose to remove the access rights so that we do not
            // accidentally give rights for a purse to some contract that is not
            // supposed to have it.
            let maybe_purse_uref =
                get_refund_purse().map(|p| PurseId::new(p.value().remove_access_rights()));
            let return_value = CLValue::from_t(maybe_purse_uref).unwrap_or_revert();
            runtime::ret(return_value);
        }
        "finalize_payment" => {
            let amount_spent: U512 = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let account: PublicKey = runtime::get_arg(2)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            finalize_payment(amount_spent, account);
        }
        _ => {}
    }
}

#[cfg(not(feature = "lib"))]
#[no_mangle]
pub extern "C" fn call() {
    delegate();
}

#[cfg(test)]
mod tests {
    use std::{cell::RefCell, iter};

    use types::{account::PublicKey, system_contract_errors::pos::Result, BlockTime, U512};

    use crate::{
        bond,
        queue::{Queue, QueueProvider},
        stakes::{Stakes, StakesProvider},
        step, unbond, BOND_DELAY, UNBOND_DELAY,
    };

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

        fn write_bonding(queue: Queue) {
            BONDING.with(|b| b.replace(queue));
        }

        fn write_unbonding(queue: Queue) {
            UNBONDING.with(|ub| ub.replace(queue));
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
        bond::<TestQueues, TestStakes>(U512::from(500), PublicKey::new(KEY2), BlockTime::new(1))
            .expect("bond validator 2");

        // Bonding becomes effective only after the delay.
        assert_stakes(&[(KEY1, 1_000)]);
        step::<TestQueues, TestStakes>(BlockTime::new(BOND_DELAY)).expect("step 1");
        assert_stakes(&[(KEY1, 1_000)]);
        step::<TestQueues, TestStakes>(BlockTime::new(1 + BOND_DELAY)).expect("step 2");
        assert_stakes(&[(KEY1, 1_000), (KEY2, 500)]);

        unbond::<TestQueues, TestStakes>(
            Some(U512::from(500)),
            PublicKey::new(KEY1),
            BlockTime::new(2),
        )
        .expect("partly unbond validator 1");

        // Unbonding becomes effective immediately.
        assert_stakes(&[(KEY1, 500), (KEY2, 500)]);
        step::<TestQueues, TestStakes>(BlockTime::new(2 + UNBOND_DELAY)).expect("step 3");
        assert_stakes(&[(KEY1, 500), (KEY2, 500)]);
    }
}
