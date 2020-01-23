// TODO(Fraser) - remove
#![allow(unused)]

use contract::args_parser::ArgsParser;
use engine_core::engine_state::{deploy_item::DeployItem, execute_request::ExecuteRequest};
use types::ProtocolVersion;

use crate::{Address, Code};

/// A single session, i.e. a single request to execute a single deploy within the test context.
pub struct Session {
    pub(crate) inner: ExecuteRequest,
}

/// Builder for a [`Session`].
pub struct SessionBuilder {
    inner: DeployItem,
    protocol_version: ProtocolVersion,
}

impl SessionBuilder {
    /// Constructs a new `SessionBuilder` containing a deploy with the provided session code and
    /// session args, and with default values for the account address, payment code, payment code
    /// args, gas price, authorization keys and protocol version.
    pub fn new(session_code: Code, session_args: impl ArgsParser) -> Self {
        unimplemented!()
    }

    /// Returns `self` with the provided account address set.
    pub fn with_address(mut self, address: Address) -> Self {
        unimplemented!()
    }

    /// Returns `self` with the provided payment code and args set.
    pub fn with_payment_code(mut self, code: Code, args: impl ArgsParser) -> Self {
        unimplemented!()
    }

    /// Returns `self` with the provided gas price set.
    pub fn with_gas_price(mut self, price: u64) -> Self {
        unimplemented!()
    }

    /// Returns `self` with the provided authorization keys set.
    pub fn with_authorization_keys(mut self, keys: &[Address]) -> Self {
        unimplemented!()
    }

    /// Returns `self` with the provided protocol version set.
    pub fn with_protocol_version(mut self, version: ProtocolVersion) -> Self {
        unimplemented!()
    }

    /// Builds the [`Session`].
    pub fn build(self) -> Session {
        // self.inner.deploy_hash should be generated here (based on?)
        unimplemented!()
    }
}
