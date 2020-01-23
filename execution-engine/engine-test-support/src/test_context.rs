use num_traits::identities::Zero;

use engine_core::engine_state::genesis::{GenesisAccount, GenesisConfig};
use engine_shared::motes::Motes;
use types::{
    account::{PublicKey, PurseId},
    AccessRights, Key, URef, U512,
};

use crate::{
    low_level::{InMemoryWasmTestBuilder, DEFAULT_GENESIS_CONFIG},
    Address, Error, Result, Session, URefAddr, Value,
};

/// Context in which to run a test of a Wasm smart contract.
pub struct TestContext {
    inner: InMemoryWasmTestBuilder,
}

impl TestContext {
    /// Runs the supplied [`Session`](struct.Session.html), asserting successful execution of the
    /// contained deploy and subsequent commit of the resulting transforms.
    pub fn run(&mut self, session: Session) -> &mut Self {
        self.inner
            .exec(session.inner)
            .expect_success()
            .commit()
            .expect_success();
        self
    }

    /// Queries for a [`Value`](struct.Value.html) stored under the given `key` and `path`.
    ///
    /// Returns an [`Error`](struct.Error.html) if not found.
    pub fn query<T: AsRef<str>>(&self, key: Address, path: &[T]) -> Result<Value> {
        let path = path.iter().map(AsRef::as_ref).collect::<Vec<_>>();
        self.inner
            .query(None, Key::Account(key), &path)
            .map(Value::new)
            .map_err(Error::from)
    }

    /// Gets the balance of the purse under the given [`URefAddr`](type.URefAddr.html).
    ///
    /// Note that this requires performing an earlier query to retrieve `purse_id_addr`.
    pub fn get_balance(&self, purse_id_addr: URefAddr) -> U512 {
        let purse_id = PurseId::new(URef::new(purse_id_addr, AccessRights::READ));
        self.inner.get_purse_balance(purse_id)
    }
}

/// Builder for a [`TestContext`].
pub struct TestContextBuilder {
    genesis_config: GenesisConfig,
}

impl TestContextBuilder {
    /// Constructs a new `TestContextBuilder` initialised with default values for an account, i.e.
    /// an account at [`DEFAULT_ACCOUNT_ADDR`](constant.DEFAULT_ACCOUNT_ADDR.html) with an initial
    /// balance of
    /// [`DEFAULT_ACCOUNT_INITIAL_BALANCE`](constant.DEFAULT_ACCOUNT_INITIAL_BALANCE.html) which
    /// will be added to the Genesis block.
    pub fn new() -> Self {
        TestContextBuilder {
            genesis_config: DEFAULT_GENESIS_CONFIG.clone(),
        }
    }

    /// Returns `self` with the provided account's details added to existing ones, for inclusion in
    /// the Genesis block.
    ///
    /// Note: `initial_balance` is in
    /// [`Motes`](https://docs.rs/casperlabs-engine-shared/latest/casperlabs_engine_shared/motes/struct.Motes.html)
    pub fn with_account(mut self, address: Address, initial_balance: U512) -> Self {
        let new_account = GenesisAccount::new(
            PublicKey::new(address),
            Motes::new(initial_balance),
            Motes::zero(),
        );
        self.genesis_config.push_account(new_account);
        self
    }

    /// Builds the [`TestContext`].
    pub fn build(self) -> TestContext {
        let mut inner = InMemoryWasmTestBuilder::default();
        inner.run_genesis(&self.genesis_config);
        TestContext { inner }
    }
}

impl Default for TestContextBuilder {
    fn default() -> Self {
        TestContextBuilder::new()
    }
}
