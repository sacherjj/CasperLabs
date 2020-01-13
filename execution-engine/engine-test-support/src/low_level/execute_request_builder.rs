use protobuf::RepeatedField;
use rand::Rng;

use contract::{
    args_parser::ArgsParser,
    value::{account::PublicKey, ProtocolVersion},
};
use engine_grpc_server::engine_server::{
    ipc::{DeployItem, ExecuteRequest},
    state,
};

use crate::low_level::{
    DeployItemBuilder, DEFAULT_BLOCK_TIME, DEFAULT_PAYMENT, STANDARD_PAYMENT_CONTRACT,
};

pub struct ExecuteRequestBuilder {
    deploy_items: Vec<DeployItem>,
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
        self.deploy_items.push(deploy);
        self
    }

    pub fn with_pre_state_hash(mut self, pre_state_hash: &[u8]) -> Self {
        self.execute_request
            .set_parent_state_hash(pre_state_hash.to_vec());
        self
    }

    pub fn with_block_time(mut self, block_time: u64) -> Self {
        self.execute_request.set_block_time(block_time);
        self
    }

    pub fn with_protocol_version(mut self, protocol_version: ProtocolVersion) -> Self {
        let mut protocol = state::ProtocolVersion::new();
        protocol.set_major(protocol_version.value().major);
        protocol.set_minor(protocol_version.value().minor);
        protocol.set_patch(protocol_version.value().patch);
        self.execute_request.set_protocol_version(protocol);
        self
    }

    pub fn build(mut self) -> ExecuteRequest {
        let mut deploys = RepeatedField::<DeployItem>::new();
        for deploy in self.deploy_items {
            deploys.push(deploy);
        }
        self.execute_request.set_deploys(deploys);
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
        let deploy_items = vec![];
        let mut execute_request = ExecuteRequest::new();
        execute_request.set_block_time(DEFAULT_BLOCK_TIME);
        execute_request.set_protocol_version(ProtocolVersion::V1_0_0.into());
        ExecuteRequestBuilder {
            deploy_items,
            execute_request,
        }
    }
}
