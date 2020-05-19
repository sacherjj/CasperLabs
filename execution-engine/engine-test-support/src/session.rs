use rand::Rng;

use contract::args_parser::ArgsParser;
use engine_core::engine_state::execute_request::ExecuteRequest;
use types::ProtocolVersion;

use crate::{
    internal::{DeployItemBuilder, ExecuteRequestBuilder, DEFAULT_PAYMENT},
    Code, PublicKey,
};

/// A single session, i.e. a single request to execute a single deploy within the test context.
pub struct Session {
    pub(crate) inner: ExecuteRequest,
}

/// Builder for a [`Session`].
pub struct SessionBuilder {
    er_builder: ExecuteRequestBuilder,
    di_builder: DeployItemBuilder,
}

impl SessionBuilder {
    /// Constructs a new `SessionBuilder` containing a deploy with the provided session code and
    /// session args, and with default values for the account address, payment code args, gas price,
    /// authorization keys and protocol version.
    pub fn new(session_code: Code, session_args: impl ArgsParser) -> Self {
        let di_builder = DeployItemBuilder::new().with_empty_payment_bytes((*DEFAULT_PAYMENT,));
        let di_builder = match session_code {
            Code::Path(path) => di_builder.with_session_code(path, session_args),
            Code::NamedKey(name) => di_builder.with_stored_session_named_key(&name, session_args),
            Code::URef(uref) => {
                di_builder.with_stored_session_uref_addr(uref.to_vec(), session_args)
            }
            Code::Hash(hash, entry_point) => {
                di_builder.with_stored_session_hash(hash, &entry_point, session_args)
            }
        };
        Self {
            er_builder: Default::default(),
            di_builder,
        }
    }

    /// Returns `self` with the provided account address set.
    pub fn with_address(mut self, address: PublicKey) -> Self {
        self.di_builder = self.di_builder.with_address(address);
        self
    }

    /// Returns `self` with the provided payment code and args set.
    pub fn with_payment_code(mut self, code: Code, args: impl ArgsParser) -> Self {
        self.di_builder = match code {
            Code::Path(path) => self.di_builder.with_payment_code(path, args),
            Code::NamedKey(name) => self.di_builder.with_stored_payment_named_key(&name, args),
            Code::URef(uref) => self
                .di_builder
                .with_stored_payment_uref_addr(uref.to_vec(), args),
            Code::Hash(hash, entry_point) => {
                self.di_builder
                    .with_stored_payment_hash(hash, &entry_point, args)
            }
        };
        self
    }

    /// Returns `self` with the provided gas price set.
    pub fn with_gas_price(mut self, price: u64) -> Self {
        self.di_builder = self.di_builder.with_gas_price(price);
        self
    }

    /// Returns `self` with the provided authorization keys set.
    pub fn with_authorization_keys(mut self, keys: &[PublicKey]) -> Self {
        self.di_builder = self.di_builder.with_authorization_keys(keys);
        self
    }

    /// Returns `self` with the provided protocol version set.
    pub fn with_protocol_version(mut self, version: ProtocolVersion) -> Self {
        self.er_builder = self.er_builder.with_protocol_version(version);
        self
    }

    /// Builds the [`Session`].
    pub fn build(self) -> Session {
        let mut rng = rand::thread_rng();
        let execute_request = self
            .er_builder
            .push_deploy(self.di_builder.with_deploy_hash(rng.gen()).build())
            .build();
        Session {
            inner: execute_request,
        }
    }
}
