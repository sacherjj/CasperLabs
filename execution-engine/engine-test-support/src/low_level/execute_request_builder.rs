use std::convert::TryInto;

use rand::Rng;

use contract::args_parser::ArgsParser;
use engine_core::engine_state::{deploy_item::DeployItem, execute_request::ExecuteRequest};
use types::{account::PublicKey, ProtocolVersion};

use crate::low_level::{
    DeployItemBuilder, DEFAULT_BLOCK_TIME, DEFAULT_PAYMENT, STANDARD_PAYMENT_CONTRACT,
};

pub struct ExecuteRequestBuilder {
    execute_request: ExecuteRequest,
}

impl ExecuteRequestBuilder {
    pub fn new() -> Self {
        Default::default()
    }

    pub fn from_deploy_item(deploy_item: DeployItem) -> Self {
        ExecuteRequestBuilder::new().push_deploy(deploy_item)
    }

    pub fn push_deploy(mut self, deploy: DeployItem) -> Self {
        self.execute_request.deploys.push(Ok(deploy));
        self
    }

    pub fn with_pre_state_hash(mut self, pre_state_hash: &[u8]) -> Self {
        self.execute_request.parent_state_hash = pre_state_hash.try_into().unwrap();
        self
    }

    pub fn with_block_time(mut self, block_time: u64) -> Self {
        self.execute_request.block_time = block_time;
        self
    }

    pub fn with_protocol_version(mut self, protocol_version: ProtocolVersion) -> Self {
        self.execute_request.protocol_version = protocol_version;
        self
    }

    pub fn build(self) -> ExecuteRequest {
        self.execute_request
    }

    pub fn standard(addr: [u8; 32], session_file: &str, session_args: impl ArgsParser) -> Self {
        let mut rng = rand::thread_rng();
        let deploy_hash: [u8; 32] = rng.gen();

        let deploy = DeployItemBuilder::new()
            .with_address(addr)
            .with_session_code(session_file, session_args)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[PublicKey::new(addr)])
            .with_deploy_hash(deploy_hash)
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy)
    }

    pub fn contract_call_by_hash(
        sender: [u8; 32],
        contract_hash: [u8; 32],
        args: impl ArgsParser,
    ) -> Self {
        let mut rng = rand::thread_rng();
        let deploy_hash: [u8; 32] = rng.gen();

        let deploy = DeployItemBuilder::new()
            .with_address(sender)
            .with_stored_session_hash(contract_hash.to_vec(), args)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[PublicKey::new(sender)])
            .with_deploy_hash(deploy_hash)
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy)
    }
}

impl Default for ExecuteRequestBuilder {
    fn default() -> Self {
        let mut execute_request: ExecuteRequest = Default::default();
        execute_request.block_time = DEFAULT_BLOCK_TIME;
        execute_request.protocol_version = ProtocolVersion::V1_0_0;
        ExecuteRequestBuilder { execute_request }
    }
}
