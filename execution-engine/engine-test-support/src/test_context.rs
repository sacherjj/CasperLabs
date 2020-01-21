use engine_core::engine_state::genesis::GenesisConfig;
use types::U512;

use crate::{
    low_level::{InMemoryWasmTestBuilder, DEFAULT_GENESIS_CONFIG},
    Address, Error, Query, Session, URefAddr, Value,
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
            .run_genesis(&DEFAULT_GENESIS_CONFIG)
            .exec(session.inner)
            .expect_success()
            .commit()
            .expect_success();
        self
    }

    /// Runs the supplied [`Query`](struct.Query.html), returning the requested
    /// [`Value`](struct.Value.html) or an [`Error`](struct.Error.html) if not found.
    pub fn query(&self, query: Query) -> Result<Value, Error> {
        unimplemented!()
    }

    /// Gets the balance of the purse under the given [`URefAddr`](type.URefAddr.html).
    ///
    /// Note that this requires performing an earlier query to retrieve `purse_id_addr`.
    pub fn get_balance(&self, purse_id_addr: URefAddr) -> U512 {
        unimplemented!()
    }
}

/// Builder for a [`TestContext`].
pub struct TestContextBuilder {
    inner: GenesisConfig,
}

impl TestContextBuilder {
    /// Constructs a new `TestContextBuilder` initialised with default values for an account.
    pub fn new() -> Self {
        unimplemented!()
    }

    /// Returns `self` with the provided account details set.
    pub fn with_account(mut self, address: Address, amount: U512) -> Self {
        unimplemented!()
    }

    /// Builds the [`TestContext`].
    pub fn build(self) -> TestContext {
        unimplemented!()
    }
}

impl Default for TestContextBuilder {
    fn default() -> Self {
        TestContextBuilder::new()
    }
}
