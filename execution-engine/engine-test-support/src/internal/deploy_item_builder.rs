use std::{collections::BTreeSet, path::Path};

use contract::args_parser::ArgsParser;
use engine_core::{
    engine_state::{deploy_item::DeployItem, executable_deploy_item::ExecutableDeployItem},
    DeployHash,
};
use types::{account::PublicKey, bytesrepr::ToBytes, URef};

use crate::internal::utils;

#[derive(Default)]
struct DeployItemData {
    pub address: Option<PublicKey>,
    pub payment_code: Option<ExecutableDeployItem>,
    pub session_code: Option<ExecutableDeployItem>,
    pub gas_price: u64,
    pub authorization_keys: BTreeSet<PublicKey>,
    pub deploy_hash: DeployHash,
}

pub struct DeployItemBuilder {
    deploy_item: DeployItemData,
}

impl DeployItemBuilder {
    pub fn new() -> Self {
        Default::default()
    }

    pub fn with_address(mut self, address: PublicKey) -> Self {
        self.deploy_item.address = Some(address);
        self
    }

    pub fn with_payment_bytes(mut self, module_bytes: Vec<u8>, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        self.deploy_item.payment_code =
            Some(ExecutableDeployItem::ModuleBytes { module_bytes, args });
        self
    }

    pub fn with_empty_payment_bytes(self, args: impl ArgsParser) -> Self {
        self.with_payment_bytes(vec![], args)
    }

    pub fn with_payment_code<T: AsRef<Path>>(self, file_name: T, args: impl ArgsParser) -> Self {
        let module_bytes = utils::read_wasm_file_bytes(file_name);
        self.with_payment_bytes(module_bytes, args)
    }

    pub fn with_stored_payment_hash(mut self, hash: Vec<u8>, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        self.deploy_item.payment_code =
            Some(ExecutableDeployItem::StoredContractByHash { hash, args });
        self
    }

    pub(crate) fn with_stored_payment_uref_addr(
        mut self,
        uref_addr: Vec<u8>,
        args: impl ArgsParser,
    ) -> Self {
        let args = Self::serialize_args(args);
        self.deploy_item.payment_code = Some(ExecutableDeployItem::StoredContractByURef {
            uref: uref_addr,
            args,
        });
        self
    }

    pub fn with_stored_payment_uref(mut self, uref: URef, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        self.deploy_item.payment_code = Some(ExecutableDeployItem::StoredContractByURef {
            uref: uref.addr().to_vec(),
            args,
        });
        self
    }

    pub fn with_stored_payment_named_key(mut self, uref_name: &str, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        self.deploy_item.payment_code = Some(ExecutableDeployItem::StoredContractByName {
            name: uref_name.to_owned(),
            args,
        });
        self
    }

    pub fn with_session_bytes(mut self, module_bytes: Vec<u8>, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        self.deploy_item.session_code =
            Some(ExecutableDeployItem::ModuleBytes { module_bytes, args });
        self
    }

    pub fn with_session_code<T: AsRef<Path>>(self, file_name: T, args: impl ArgsParser) -> Self {
        let module_bytes = utils::read_wasm_file_bytes(file_name);
        self.with_session_bytes(module_bytes, args)
    }

    pub fn with_stored_session_hash(mut self, hash: Vec<u8>, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        self.deploy_item.session_code =
            Some(ExecutableDeployItem::StoredContractByHash { hash, args });
        self
    }

    pub(crate) fn with_stored_session_uref_addr(
        mut self,
        uref_addr: Vec<u8>,
        args: impl ArgsParser,
    ) -> Self {
        let args = Self::serialize_args(args);
        self.deploy_item.session_code = Some(ExecutableDeployItem::StoredContractByURef {
            uref: uref_addr,
            args,
        });
        self
    }

    pub fn with_stored_session_uref(mut self, uref: URef, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        self.deploy_item.session_code = Some(ExecutableDeployItem::StoredContractByURef {
            uref: uref.addr().to_vec(),
            args,
        });
        self
    }

    pub fn with_stored_session_named_key(mut self, uref_name: &str, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        self.deploy_item.session_code = Some(ExecutableDeployItem::StoredContractByName {
            name: uref_name.to_owned(),
            args,
        });
        self
    }

    pub fn with_authorization_keys<T: Clone + Into<PublicKey>>(
        mut self,
        authorization_keys: &[T],
    ) -> Self {
        self.deploy_item.authorization_keys = authorization_keys
            .iter()
            .cloned()
            .map(|v| v.into())
            .collect();
        self
    }

    pub fn with_gas_price(mut self, gas_price: u64) -> Self {
        self.deploy_item.gas_price = gas_price;
        self
    }

    pub fn with_deploy_hash(mut self, hash: [u8; 32]) -> Self {
        self.deploy_item.deploy_hash = hash;
        self
    }

    pub fn build(self) -> DeployItem {
        DeployItem {
            address: self
                .deploy_item
                .address
                .unwrap_or_else(|| PublicKey::ed25519_from([0u8; 32])),
            session: self.deploy_item.session_code.unwrap(),
            payment: self.deploy_item.payment_code.unwrap(),
            gas_price: self.deploy_item.gas_price,
            authorization_keys: self.deploy_item.authorization_keys,
            deploy_hash: self.deploy_item.deploy_hash,
        }
    }

    fn serialize_args(args: impl ArgsParser) -> Vec<u8> {
        args.parse()
            .expect("should convert to `Vec<CLValue>`")
            .into_bytes()
            .expect("should serialize args")
    }
}

impl Default for DeployItemBuilder {
    fn default() -> Self {
        let mut deploy_item: DeployItemData = Default::default();
        deploy_item.gas_price = 1;
        DeployItemBuilder { deploy_item }
    }
}
