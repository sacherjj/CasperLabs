use contract::args_parser::ArgsParser;
use engine_grpc_server::engine_server::ipc::{
    DeployCode, DeployItem, DeployPayload, StoredContractHash, StoredContractName,
    StoredContractURef,
};
use types::{account::PublicKey, bytesrepr::ToBytes, URef};

use crate::low_level::utils;

pub struct DeployItemBuilder {
    deploy_item: DeployItem,
}

impl DeployItemBuilder {
    pub fn new() -> Self {
        Default::default()
    }

    pub fn with_address(mut self, address: [u8; 32]) -> Self {
        self.deploy_item.set_address(address.to_vec());
        self
    }

    pub fn with_payment_code(mut self, file_name: &str, args: impl ArgsParser) -> Self {
        let wasm_bytes = utils::read_wasm_file_bytes(file_name);
        let args = Self::serialize_args(args);
        let mut deploy_code = DeployCode::new();
        deploy_code.set_args(args);
        deploy_code.set_code(wasm_bytes);
        let mut payment = DeployPayload::new();
        payment.set_deploy_code(deploy_code);
        self.deploy_item.set_payment(payment);
        self
    }

    pub fn with_stored_payment_hash(mut self, hash: Vec<u8>, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        let mut item = StoredContractHash::new();
        item.set_args(args);
        item.set_hash(hash);
        let mut payment = DeployPayload::new();
        payment.set_stored_contract_hash(item);
        self.deploy_item.set_payment(payment);
        self
    }

    pub fn with_stored_payment_uref(mut self, uref: URef, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        let mut item = StoredContractURef::new();
        item.set_args(args);
        item.set_uref(uref.addr().to_vec());
        let mut payment = DeployPayload::new();
        payment.set_stored_contract_uref(item);
        self.deploy_item.set_payment(payment);
        self
    }

    pub fn with_stored_payment_named_key(mut self, uref_name: &str, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        let mut item = StoredContractName::new();
        item.set_args(args);
        item.set_stored_contract_name(uref_name.to_owned()); // <-- named uref
        let mut payment = DeployPayload::new();
        payment.set_stored_contract_name(item);
        self.deploy_item.set_payment(payment);
        self
    }

    pub fn with_session_code(mut self, file_name: &str, args: impl ArgsParser) -> Self {
        let wasm_bytes = utils::read_wasm_file_bytes(file_name);
        let args = Self::serialize_args(args);
        let mut deploy_code = DeployCode::new();
        deploy_code.set_code(wasm_bytes);
        deploy_code.set_args(args);
        let mut session = DeployPayload::new();
        session.set_deploy_code(deploy_code);
        self.deploy_item.set_session(session);
        self
    }

    pub fn with_stored_session_hash(mut self, hash: Vec<u8>, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        let mut item: StoredContractHash = StoredContractHash::new();
        item.set_args(args);
        item.set_hash(hash);
        let mut session = DeployPayload::new();
        session.set_stored_contract_hash(item);
        self.deploy_item.set_session(session);
        self
    }

    pub fn with_stored_session_uref(mut self, uref: URef, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        let mut item: StoredContractURef = StoredContractURef::new();
        item.set_args(args);
        item.set_uref(uref.addr().to_vec());
        let mut payment = DeployPayload::new();
        payment.set_stored_contract_uref(item);
        self.deploy_item.set_session(payment);
        self
    }

    pub fn with_stored_session_named_key(mut self, uref_name: &str, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        let mut item = StoredContractName::new();
        item.set_args(args);
        item.set_stored_contract_name(uref_name.to_owned()); // <-- named uref
        let mut session = DeployPayload::new();
        session.set_stored_contract_name(item);
        self.deploy_item.set_session(session);
        self
    }

    pub fn with_authorization_keys(mut self, authorization_keys: &[PublicKey]) -> Self {
        let authorization_keys = authorization_keys
            .iter()
            .map(|public_key| public_key.value().to_vec())
            .collect();
        self.deploy_item.set_authorization_keys(authorization_keys);
        self
    }

    pub fn with_deploy_hash(mut self, hash: [u8; 32]) -> Self {
        self.deploy_item.set_deploy_hash(hash.to_vec());
        self
    }

    pub fn build(self) -> DeployItem {
        self.deploy_item
    }

    fn serialize_args(args: impl ArgsParser) -> Vec<u8> {
        args.parse_to_vec_u8()
            .expect("should convert to `Vec<CLValue>`")
            .into_bytes()
            .expect("should serialize args")
    }
}

impl Default for DeployItemBuilder {
    fn default() -> Self {
        let mut deploy_item = DeployItem::new();
        deploy_item.set_gas_price(1);
        DeployItemBuilder { deploy_item }
    }
}
