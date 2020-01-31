#![cfg_attr(not(feature = "std"), no_std)]

extern crate alloc;

mod mint_provider;
mod queue;
mod queue_provider;
mod runtime_provider;
mod stakes;
mod stakes_provider;

use types::{
    account::{PublicKey, PurseId},
    system_contract_errors::pos::{Error, Result},
    AccessRights, TransferredTo, URef, U512,
};

pub use crate::{
    mint_provider::MintProvider, queue::Queue, queue_provider::QueueProvider,
    runtime_provider::RuntimeProvider, stakes::Stakes, stakes_provider::StakesProvider,
};

pub trait ProofOfStake<M, Q, R, S>
where
    M: MintProvider,
    Q: QueueProvider,
    R: RuntimeProvider,
    S: StakesProvider,
{
    fn bond(&self, validator: PublicKey, amount: U512, source_uref: URef) -> Result<()> {
        if amount.is_zero() {
            return Err(Error::BondTooSmall);
        }
        let source = PurseId::new(source_uref);
        let pos_purse = internal::get_bonding_purse::<R>()?;
        let timestamp = R::get_block_time();
        // Transfer `amount` from the `source` purse to PoS internal purse. POS_PURSE is a constant,
        // it is the PurseID of the proof-of-stake contract's own purse.
        M::transfer_from_purse_to_purse(source, pos_purse, amount)
            .map_err(|_| Error::BondTransferFailed)?;
        internal::bond::<Q, S>(amount, validator, timestamp)?;

        // TODO: Remove this and set nonzero delays once the system calls `step` in each block.
        let unbonds = internal::step::<Q, S>(timestamp)?;
        for entry in unbonds {
            let _: TransferredTo =
                M::transfer_from_purse_to_account(pos_purse, entry.validator, entry.amount)
                    .map_err(|_| Error::BondTransferFailed)?;
        }
        Ok(())
    }

    fn unbond(&self, validator: PublicKey, maybe_amount: Option<U512>) -> Result<()> {
        let pos_purse = internal::get_bonding_purse::<R>()?;
        let timestamp = R::get_block_time();
        internal::unbond::<Q, S>(maybe_amount, validator, timestamp)?;

        // TODO: Remove this and set nonzero delays once the system calls `step` in each block.
        let unbonds = internal::step::<Q, S>(timestamp)?;
        for entry in unbonds {
            M::transfer_from_purse_to_account(pos_purse, entry.validator, entry.amount)
                .map_err(|_| Error::UnbondTransferFailed)?;
        }
        Ok(())
    }

    fn step(&self) -> Result<()> {
        let pos_purse = internal::get_bonding_purse::<R>()?;
        let timestamp = R::get_block_time();
        // This is called by the system in every block.
        let unbonds = internal::step::<Q, S>(timestamp)?;

        // Mateusz: Moved outside of `step` function so that it [step] can be unit tested.
        for entry in unbonds {
            // TODO: We currently ignore `TransferResult::TransferError`s here, since we
            // can't recover from them and we shouldn't retry indefinitely.
            // That would mean the contract just keeps the money forever,
            // though.
            let _ = M::transfer_from_purse_to_account(pos_purse, entry.validator, entry.amount);
        }
        Ok(())
    }

    fn get_payment_purse(&self) -> Result<PurseId> {
        let purse = internal::get_payment_purse::<R>()?;
        // Limit the access rights so only balance query and deposit are allowed.
        Ok(PurseId::new(URef::new(
            purse.value().addr(),
            AccessRights::READ_ADD,
        )))
    }

    fn set_refund_purse(&self, purse_id: PurseId) -> Result<()> {
        internal::set_refund::<R>(purse_id.value())
    }

    fn get_refund_purse(&self) -> Result<Option<PurseId>> {
        // We purposely choose to remove the access rights so that we do not
        // accidentally give rights for a purse to some contract that is not
        // supposed to have it.
        let maybe_purse = internal::get_refund_purse::<R>()?;
        Ok(maybe_purse.map(|p| PurseId::new(p.value().remove_access_rights())))
    }

    fn finalize_payment(&self, amount_spent: U512, account: PublicKey) -> Result<()> {
        internal::finalize_payment::<M, R>(amount_spent, account)
    }
}

mod internal {
    use alloc::vec::Vec;

    use types::{
        account::{PublicKey, PurseId},
        key::Key,
        system_contract_errors::pos::{Error, PurseLookupError, Result},
        BlockTime, Phase, URef, U512,
    };

    use crate::{
        mint_provider::MintProvider, queue::QueueEntry, queue_provider::QueueProvider,
        runtime_provider::RuntimeProvider, stakes_provider::StakesProvider,
    };

    /// Account used to run system functions (in particular `finalize_payment`).
    const SYSTEM_ACCOUNT: [u8; 32] = [0u8; 32];

    /// The uref name where the PoS purse is stored. It contains all staked motes, and all unbonded
    /// motes that are yet to be paid out.
    const BONDING_PURSE_KEY: &str = "pos_bonding_purse";

    /// The uref name where the PoS accepts payment for computation on behalf of validators.
    const PAYMENT_PURSE_KEY: &str = "pos_payment_purse";

    /// The uref name where the PoS holds validator earnings before distributing them.
    const REWARDS_PURSE_KEY: &str = "pos_rewards_purse";

    /// The uref name where the PoS will refund unused payment back to the user. The uref this name
    /// corresponds to is set by the user.
    const REFUND_PURSE_KEY: &str = "pos_refund_purse";

    /// The time from a bonding request until the bond becomes effective and part of the stake.
    const BOND_DELAY: u64 = 0;

    /// The time from an unbonding request until the stakes are paid out.
    const UNBOND_DELAY: u64 = 0;

    /// The maximum number of pending bonding requests.
    const MAX_BOND_LEN: usize = 100;

    /// The maximum number of pending unbonding requests.
    const MAX_UNBOND_LEN: usize = 1000;

    /// Enqueues the deploy's creator for becoming a validator. The bond `amount` is paid from the
    /// purse `source`.
    pub fn bond<Q: QueueProvider, S: StakesProvider>(
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

    /// Enqueues the deploy's creator for unbonding. Their vote weight as a validator is decreased
    /// immediately, but the funds will only be released after a delay. If `maybe_amount` is `None`,
    /// all funds are enqueued for withdrawal, terminating the validator status.
    pub fn unbond<Q: QueueProvider, S: StakesProvider>(
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
        // TODO: Make sure the destination is valid and the amount can be paid. The actual payment
        // will be made later, after the unbonding delay. contract_api::transfer_dry_run(POS_PURSE,
        // dest, amount)?;
        queue.push(validator, payout, timestamp)?;
        Q::write_unbonding(queue);
        Ok(())
    }

    /// Removes all due requests from the queues and applies them.
    pub fn step<Q: QueueProvider, S: StakesProvider>(
        timestamp: BlockTime,
    ) -> Result<Vec<QueueEntry>> {
        let mut bonding_queue = Q::read_bonding();
        let mut unbonding_queue = Q::read_unbonding();

        let bonds = bonding_queue.pop_due(timestamp.saturating_sub(BlockTime::new(BOND_DELAY)));
        let unbonds =
            unbonding_queue.pop_due(timestamp.saturating_sub(BlockTime::new(UNBOND_DELAY)));

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
    fn get_purse_id<R: RuntimeProvider>(
        name: &str,
    ) -> core::result::Result<PurseId, PurseLookupError> {
        R::get_key(name)
            .ok_or(PurseLookupError::KeyNotFound)
            .and_then(|key| match key {
                Key::URef(uref) => Ok(PurseId::new(uref)),
                _ => Err(PurseLookupError::KeyUnexpectedType),
            })
    }

    /// Returns the purse for accepting payment for tranasactions.
    pub fn get_payment_purse<R: RuntimeProvider>() -> Result<PurseId> {
        get_purse_id::<R>(PAYMENT_PURSE_KEY).map_err(PurseLookupError::payment)
    }

    /// Returns the purse for holding bonds
    pub fn get_bonding_purse<R: RuntimeProvider>() -> Result<PurseId> {
        get_purse_id::<R>(BONDING_PURSE_KEY).map_err(PurseLookupError::bonding)
    }

    /// Returns the purse for holding validator earnings
    pub fn get_rewards_purse<R: RuntimeProvider>() -> Result<PurseId> {
        get_purse_id::<R>(REWARDS_PURSE_KEY).map_err(PurseLookupError::rewards)
    }

    /// Sets the purse where refunds (excess funds not spent to pay for computation) will be sent.
    /// Note that if this function is never called, the default location is the main purse of the
    /// deployer's account.
    pub fn set_refund<R: RuntimeProvider>(purse_id: URef) -> Result<()> {
        if let Phase::Payment = R::get_phase() {
            R::put_key(REFUND_PURSE_KEY, Key::URef(purse_id));
            return Ok(());
        }
        Err(Error::SetRefundPurseCalledOutsidePayment)
    }

    /// Returns the currently set refund purse.
    pub fn get_refund_purse<R: RuntimeProvider>() -> Result<Option<PurseId>> {
        match get_purse_id::<R>(REFUND_PURSE_KEY) {
            Ok(purse_id) => Ok(Some(purse_id)),
            Err(PurseLookupError::KeyNotFound) => Ok(None),
            Err(PurseLookupError::KeyUnexpectedType) => Err(Error::RefundPurseKeyUnexpectedType),
        }
    }

    /// Transfers funds from the payment purse to the validator rewards purse, as well as to the
    /// refund purse, depending on how much was spent on the computation. This function maintains
    /// the invariant that the balance of the payment purse is zero at the beginning and end of each
    /// deploy and that the refund purse is unset at the beginning and end of each deploy.
    pub fn finalize_payment<M: MintProvider, R: RuntimeProvider>(
        amount_spent: U512,
        account: PublicKey,
    ) -> Result<()> {
        let caller = R::get_caller();
        if caller.value() != SYSTEM_ACCOUNT {
            return Err(Error::SystemFunctionCalledByUserAccount);
        }

        let payment_purse = get_payment_purse::<R>()?;
        let total = match M::get_balance(payment_purse) {
            Some(balance) => balance,
            None => return Err(Error::PaymentPurseBalanceNotFound),
        };
        if total < amount_spent {
            return Err(Error::InsufficientPaymentForAmountSpent);
        }
        let refund_amount = total - amount_spent;

        let rewards_purse = get_rewards_purse::<R>()?;
        let refund_purse = get_refund_purse::<R>()?;
        R::remove_key(REFUND_PURSE_KEY); //unset refund purse after reading it

        // pay validators
        M::transfer_from_purse_to_purse(payment_purse, rewards_purse, amount_spent)
            .map_err(|_| Error::FailedTransferToRewardsPurse)?;

        if refund_amount.is_zero() {
            return Ok(());
        }

        // give refund
        let refund_purse = match refund_purse {
            Some(purse_id) => purse_id,
            None => return refund_to_account::<M>(payment_purse, account, refund_amount),
        };

        // in case of failure to transfer to refund purse we fall back on the account's main purse
        if M::transfer_from_purse_to_purse(payment_purse, refund_purse, refund_amount).is_err() {
            return refund_to_account::<M>(payment_purse, account, refund_amount);
        }

        Ok(())
    }

    pub fn refund_to_account<M: MintProvider>(
        payment_purse: PurseId,
        account: PublicKey,
        amount: U512,
    ) -> Result<()> {
        match M::transfer_from_purse_to_account(payment_purse, account, amount) {
            Ok(_) => Ok(()),
            Err(_) => Err(Error::FailedTransferToAccountPurse),
        }
    }

    #[cfg(test)]
    mod tests {
        extern crate std;

        use std::{cell::RefCell, iter, thread_local};

        use types::{account::PublicKey, system_contract_errors::pos::Result, BlockTime, U512};

        use super::{bond, step, unbond, BOND_DELAY, UNBOND_DELAY};
        use crate::{
            queue::Queue, queue_provider::QueueProvider, stakes::Stakes,
            stakes_provider::StakesProvider,
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
            bond::<TestQueues, TestStakes>(
                U512::from(500),
                PublicKey::new(KEY2),
                BlockTime::new(1),
            )
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
}
