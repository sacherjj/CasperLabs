use num_traits::identities::Zero;

use engine_core::engine_state::{
    genesis::{GenesisAccount, GenesisConfig},
    run_genesis_request::RunGenesisRequest,
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
    /// Runs the supplied [`Session`], asserting successful execution of the contained deploy and
    /// subsequent commit of the resulting transforms.
    pub fn run(&mut self, session: Session) -> &mut Self {
        self.inner
            .exec(session.inner)
            .expect_success()
            .commit()
            .expect_success();
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
