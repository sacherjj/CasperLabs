use std::convert::{TryFrom, TryInto};

use engine_core::engine_state::{
    execute_request::ExecuteRequest, execution_result::ExecutionResult,
};
use engine_shared::newtypes::BLAKE2B_DIGEST_LENGTH;

use crate::engine_server::{ipc, mappings::MappingError};

impl TryFrom<ipc::ExecuteRequest> for ExecuteRequest {
    type Error = ipc::ExecuteResponse;

    fn try_from(mut request: ipc::ExecuteRequest) -> Result<Self, Self::Error> {
        let parent_state_hash = {
            let parent_state_hash = request.take_parent_state_hash();
            let length = parent_state_hash.len();
            if length != BLAKE2B_DIGEST_LENGTH {
                let mut error = ipc::RootNotFound::new();
                error.set_hash(parent_state_hash);
                let mut result = ipc::ExecuteResponse::new();
                result.set_missing_parent(error);
                return Err(result);
            }
            parent_state_hash.as_slice().try_into().map_err(|_| {
                let mut error = ipc::RootNotFound::new();
                error.set_hash(parent_state_hash.clone());
                let mut result = ipc::ExecuteResponse::new();
                result.set_missing_parent(error);
                result
            })?
        };

        let block_time = request.get_block_time();

        let deploys = Into::<Vec<_>>::into(request.take_deploys())
            .into_iter()
            .map(|deploy_item| {
                deploy_item
                    .try_into()
                    .map_err(|err: MappingError| ExecutionResult::precondition_failure(err.into()))
            })
            .collect();

        let protocol_version = request.take_protocol_version().into();

        Ok(ExecuteRequest::new(
            parent_state_hash,
            block_time,
            deploys,
            protocol_version,
        ))
    }
}
