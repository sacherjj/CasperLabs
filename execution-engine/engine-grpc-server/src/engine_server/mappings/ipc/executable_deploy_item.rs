use engine_core::engine_state::executable_deploy_item::ExecutableDeployItem;

use crate::engine_server::ipc::DeployPayload_oneof_payload as ProtobufDeployPayloadEnum;

impl From<ProtobufDeployPayloadEnum> for ExecutableDeployItem {
    fn from(pb_deploy_payload: ProtobufDeployPayloadEnum) -> Self {
        match pb_deploy_payload {
            ProtobufDeployPayloadEnum::deploy_code(pb_deploy_code) => {
                ExecutableDeployItem::ModuleBytes {
                    module_bytes: pb_deploy_code.code,
                    args: pb_deploy_code.args,
                }
            }
            ProtobufDeployPayloadEnum::stored_contract_hash(pb_stored_contract_hash) => {
                ExecutableDeployItem::StoredContractByHash {
                    hash: pb_stored_contract_hash.hash,
                    args: pb_stored_contract_hash.args,
                }
            }
            ProtobufDeployPayloadEnum::stored_contract_name(pb_stored_contract_name) => {
                ExecutableDeployItem::StoredContractByName {
                    name: pb_stored_contract_name.stored_contract_name,
                    args: pb_stored_contract_name.args,
                }
            }
            ProtobufDeployPayloadEnum::stored_contract_uref(pb_stored_contract_uref) => {
                ExecutableDeployItem::StoredContractByURef {
                    uref: pb_stored_contract_uref.uref,
                    args: pb_stored_contract_uref.args,
                }
            }
        }
    }
}
