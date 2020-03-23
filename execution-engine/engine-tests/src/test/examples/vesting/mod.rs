mod vesting_test;

use engine_test_support::{DEFAULT_ACCOUNT_ADDR, DEFAULT_ACCOUNT_INITIAL_BALANCE};
use types::{account::PublicKey, U512};

use vesting_test::{VestingConfig, VestingTest};

const FAUCET: PublicKey = DEFAULT_ACCOUNT_ADDR;
const RECIPIENT: PublicKey = PublicKey::ed25519_from([2u8; 32]);
const ADMIN: PublicKey = PublicKey::ed25519_from([3u8; 32]);

const NOT_ADMIN_ERROR_CODE: u32 = 65544;
const NOT_RECIPIENT_ERROR_CODE: u32 = 65545;

#[ignore]
#[test]
fn test_vesting_deployment() {
    let config: VestingConfig = Default::default();
    VestingTest::new(FAUCET, ADMIN, RECIPIENT, &config)
        .assert_cliff_timestamp(&config.cliff_timestamp)
        .assert_cliff_amount(&config.cliff_amount)
        .assert_drip_duration(&config.drip_duration)
        .assert_drip_amount(&config.drip_amount)
        .assert_total_amount(&config.total_amount)
        .assert_admin_release_duration(&config.admin_release_duration)
        .assert_released_amount(&0.into())
        .assert_unpaused()
        .assert_on_pause_duration(&0.into())
        .assert_last_pause_timestamp(&0.into())
        .assert_clx_vesting_balance(&config.total_amount);
}

#[ignore]
#[test]
fn test_pause_by_admin() {
    let config: VestingConfig = Default::default();
    let init_balance = U512::from(500_000_000);
    VestingTest::new(FAUCET, ADMIN, RECIPIENT, &config)
        .call_clx_transfer_with_success(FAUCET, ADMIN, init_balance)
        .with_block_timestamp(10)
        .call_vesting_pause(ADMIN)
        .assert_success_status_and_commit()
        .assert_paused()
        .assert_last_pause_timestamp(&10.into());
}

#[ignore]
#[test]
fn test_pause_by_non_admin_error() {
    let config: VestingConfig = Default::default();
    let init_balance = U512::from(500_000_000);
    VestingTest::new(FAUCET, ADMIN, RECIPIENT, &config)
        .call_clx_transfer_with_success(FAUCET, RECIPIENT, init_balance)
        .call_vesting_pause(RECIPIENT)
        .assert_failure_with_exit_code(NOT_ADMIN_ERROR_CODE);
}

#[ignore]
#[test]
fn test_unpause_by_admin() {
    let config: VestingConfig = Default::default();
    let init_balance = U512::from(500_000_000);
    VestingTest::new(FAUCET, ADMIN, RECIPIENT, &config)
        .call_clx_transfer_with_success(FAUCET, ADMIN, init_balance)
        .call_vesting_pause(ADMIN)
        .assert_success_status_and_commit()
        .with_block_timestamp(10)
        .call_vesting_unpause(ADMIN)
        .assert_success_status_and_commit();
}

#[ignore]
#[test]
fn test_unpause_by_non_admin_error() {
    let config: VestingConfig = Default::default();
    let init_balance = U512::from(500_000_000);
    VestingTest::new(FAUCET, ADMIN, RECIPIENT, &config)
        .call_clx_transfer_with_success(FAUCET, ADMIN, init_balance)
        .call_clx_transfer_with_success(FAUCET, RECIPIENT, init_balance)
        .call_vesting_pause(ADMIN)
        .assert_success_status_and_commit()
        .call_vesting_unpause(RECIPIENT)
        .assert_failure_with_exit_code(NOT_ADMIN_ERROR_CODE);
}

#[ignore]
#[test]
fn test_withdraw() {
    // Use FAUCET as RECIPIENT.
    let config: VestingConfig = Default::default();
    let init_balance = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);
    let withdraw_amount = U512::from(1);
    VestingTest::new(FAUCET, ADMIN, FAUCET, &config)
        .with_block_timestamp(11)
        .call_withdraw(FAUCET, withdraw_amount)
        .assert_success_status_and_commit()
        .assert_released_amount(&withdraw_amount)
        .assert_clx_vesting_balance(&(config.total_amount - withdraw_amount))
        .assert_clx_account_balance_no_gas(
            FAUCET,
            init_balance + withdraw_amount - config.total_amount,
        );
}

#[ignore]
#[test]
fn test_withdraw_not_recipient_error() {
    let config: VestingConfig = Default::default();
    let withdraw_amount = U512::from(1);
    VestingTest::new(FAUCET, ADMIN, RECIPIENT, &config)
        .call_withdraw(FAUCET, withdraw_amount)
        .assert_failure_with_exit_code(NOT_RECIPIENT_ERROR_CODE);
}

#[ignore]
#[test]
fn test_admin_release() {
    // Use FAUCET as ADMIN.
    let config: VestingConfig = Default::default();
    let init_balance = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);
    VestingTest::new(FAUCET, FAUCET, RECIPIENT, &config)
        .call_vesting_pause(FAUCET)
        .assert_success_status_and_commit()
        .with_block_timestamp(config.admin_release_duration.as_u64())
        .call_admin_release(FAUCET)
        .assert_success_status_and_commit()
        .assert_clx_vesting_balance(&0.into())
        .assert_released_amount(&config.total_amount)
        .assert_clx_account_balance_no_gas(FAUCET, init_balance);
}

#[ignore]
#[test]
fn test_admin_release_not_admin_error() {
    let config: VestingConfig = Default::default();
    VestingTest::new(FAUCET, ADMIN, RECIPIENT, &config)
        .call_admin_release(FAUCET)
        .assert_failure_with_exit_code(NOT_ADMIN_ERROR_CODE);
}
