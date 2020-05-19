use num_traits::identities::Zero;

use engine_core::engine_state::{
    genesis::{GenesisAccount, GenesisConfig},
    run_genesis_request::RunGenesisRequest,
    CONV_RATE,
};

use engine_shared::motes::Motes;
use types::{AccessRights, Key, URef, U512};

use crate::{
    internal::{InMemoryWasmTestBuilder, DEFAULT_GENESIS_CONFIG, DEFAULT_GENESIS_CONFIG_HASH},
    Error, PublicKey, Result, Session, URefAddr, Value,
};

/// Context in which to run a test of a Wasm smart contract.
pub struct TestContext {
    inner: InMemoryWasmTestBuilder,
}

impl TestContext {
    /// Runs the supplied [`Session`], asserting successful execution of the contained deploy
    ///
    /// if Session.expect_success (default) will panic if failure.  (Allows cases where failure is
    /// expected) if Session.check_transfer_success is given, will verify transfer balances
    /// including gas used. if Session.commit (default) will commit resulting transforms.
    pub fn run(&mut self, session: Session) -> &mut Self {
        // Pre run caching of balances if needed
        let (maybe_source_initial_balance, maybe_target_initial_balance) = {
            match &session.check_transfer_success {
                None => (None, None),
                Some(session_transfer_info) => {
                    let source_initial_balance =
                        Motes::new(self.get_balance(session_transfer_info.source_purse.addr()));

                    let maybe_target_initial_balance: Option<Motes> = {
                        match session_transfer_info.target_purse {
                            None => None,
                            Some(target_purse) => {
                                let target_initial_balance = self.get_balance(target_purse.addr());
                                Some(Motes::new(target_initial_balance))
                            }
                        }
                    };
                    (Some(source_initial_balance), maybe_target_initial_balance)
                }
            }
        };

        let builder = self.inner.exec(session.inner);
        if session.expect_success {
            let builder = builder.expect_success();
        }
        if session.commit {
            let builder = builder.commit();
        }

        // Post run assertions if needed
        let _ = match &session.check_transfer_success {
            None => (),
            Some(session_transfer_info) => {
                if let Some(target_purse) = session_transfer_info.target_purse {
                    let target_ending_balance = {
                        let target_ending_balance = (&self).get_balance(target_purse.addr());
                        Motes::new(target_ending_balance)
                    };

                    assert_eq!(
                        maybe_target_initial_balance.expect("target initial balance")
                            + session_transfer_info.transfer_amount,
                        target_ending_balance,
                        "incorrect target balance"
                    );
                };

                let source_ending_balance = {
                    let source_ending_balance =
                        self.get_balance(session_transfer_info.source_purse.addr());
                    Motes::new(source_ending_balance)
                };

                let gas_cost = {
                    let gas_cost = builder.last_exec_gas_cost();
                    gas_cost
                };
                assert_eq!(
                    maybe_source_initial_balance.expect("source initial balance")
                        - session_transfer_info.transfer_amount
                        - Motes::from_gas(gas_cost, CONV_RATE).expect("motes from gas"),
                    source_ending_balance,
                    "incorrect source balance"
                );
            }
        };
        self
    }

    /// Queries for a [`Value`] stored under the given `key` and `path`.
    ///
    /// Returns an [`Error`] if not found.
    pub fn query<T: AsRef<str>>(&self, key: PublicKey, path: &[T]) -> Result<Value> {
        let path = path.iter().map(AsRef::as_ref).collect::<Vec<_>>();
        self.inner
            .query(None, Key::Account(key), &path)
            .map(Value::new)
            .map_err(Error::from)
    }

    /// Gets the balance of the purse under the given [`URefAddr`].
    ///
    /// Note that this requires performing an earlier query to retrieve `purse_addr`.
    pub fn get_balance(&self, purse_addr: URefAddr) -> U512 {
        let purse = URef::new(purse_addr, AccessRights::READ);
        self.inner.get_purse_balance(purse)
    }

    /// Gets the main purse Uref from an account
    pub fn get_main_purse_address(&self, account_key: PublicKey) -> Option<URef> {
        match self.inner.get_account(account_key) {
            None => None,
            Some(account) => Some(account.main_purse()),
        }
    }
}

/// Builder for a [`TestContext`].
pub struct TestContextBuilder {
    genesis_config: GenesisConfig,
}

impl TestContextBuilder {
    /// Constructs a new `TestContextBuilder` initialised with default values for an account, i.e.
    /// an account at [`DEFAULT_ACCOUNT_ADDR`](crate::DEFAULT_ACCOUNT_ADDR) with an initial balance
    /// of [`DEFAULT_ACCOUNT_INITIAL_BALANCE`](crate::DEFAULT_ACCOUNT_INITIAL_BALANCE) which will be
    /// added to the Genesis block.
    pub fn new() -> Self {
        TestContextBuilder {
            genesis_config: DEFAULT_GENESIS_CONFIG.clone(),
        }
    }

    /// Returns `self` with the provided account's details added to existing ones, for inclusion in
    /// the Genesis block.
    ///
    /// Note: `initial_balance` represents the number of motes.
    pub fn with_account(mut self, address: PublicKey, initial_balance: U512) -> Self {
        let new_account = GenesisAccount::new(address, Motes::new(initial_balance), Motes::zero());
        self.genesis_config
            .ee_config_mut()
            .push_account(new_account);
        self
    }

    /// Builds the [`TestContext`].
    pub fn build(self) -> TestContext {
        let mut inner = InMemoryWasmTestBuilder::default();
        let run_genesis_request = RunGenesisRequest::new(
            *DEFAULT_GENESIS_CONFIG_HASH,
            self.genesis_config.protocol_version(),
            self.genesis_config.take_ee_config(),
        );
        inner.run_genesis(&run_genesis_request);
        TestContext { inner }
    }
}

impl Default for TestContextBuilder {
    fn default() -> Self {
        TestContextBuilder::new()
    }
}
