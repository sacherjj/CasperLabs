use engine_core::engine_state::executable_deploy_item::ExecutableDeployItem;

use crate::engine_server::ipc::{DeployPayload, DeployPayload_oneof_payload};

impl From<DeployPayload_oneof_payload> for ExecutableDeployItem {
    fn from(pb_deploy_payload: DeployPayload_oneof_payload) -> Self {
        match pb_deploy_payload {
            DeployPayload_oneof_payload::deploy_code(pb_deploy_code) => {
                ExecutableDeployItem::ModuleBytes {
                    module_bytes: pb_deploy_code.code,
                    args: pb_deploy_code.args,
                }
            }
            DeployPayload_oneof_payload::stored_contract_hash(pb_stored_contract_hash) => {
                ExecutableDeployItem::StoredContractByHash {
                    hash: pb_stored_contract_hash.hash,
                    args: pb_stored_contract_hash.args,
                }
            }
            DeployPayload_oneof_payload::stored_contract_name(pb_stored_contract_name) => {
                ExecutableDeployItem::StoredContractByName {
                    name: pb_stored_contract_name.stored_contract_name,
                    args: pb_stored_contract_name.args,
                }
            }
            DeployPayload_oneof_payload::stored_contract_uref(pb_stored_contract_uref) => {
                ExecutableDeployItem::StoredContractByURef {
                    uref: pb_stored_contract_uref.uref,
                    args: pb_stored_contract_uref.args,
                }
            }
        }
    }
}

impl From<ExecutableDeployItem> for DeployPayload {
    fn from(edi: ExecutableDeployItem) -> Self {
        let mut result = DeployPayload::new();
        match edi {
            ExecutableDeployItem::ModuleBytes { module_bytes, args } => {
                let code = result.mut_deploy_code();
                code.set_code(module_bytes);
                code.set_args(args);
            }
            ExecutableDeployItem::StoredContractByHash { hash, args } => {
                let inner = result.mut_stored_contract_hash();
                inner.set_hash(hash);
                inner.set_args(args);
            }
            ExecutableDeployItem::StoredContractByName { name, args } => {
                let inner = result.mut_stored_contract_name();
                inner.set_stored_contract_name(name);
                inner.set_args(args);
            }
            ExecutableDeployItem::StoredContractByURef { uref, args } => {
                let inner = result.mut_stored_contract_uref();
                inner.set_uref(uref);
                inner.set_args(args);
            }
        }
        result
    }
}
