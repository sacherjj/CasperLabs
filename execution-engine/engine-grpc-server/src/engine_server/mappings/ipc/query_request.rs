use std::convert::{TryFrom, TryInto};

use engine_core::engine_state::query::QueryRequest;
use engine_shared::newtypes::BLAKE2B_DIGEST_LENGTH;

use crate::engine_server::{ipc::QueryRequest as ProtobufQueryRequest, mappings::MappingError};

impl TryFrom<ProtobufQueryRequest> for QueryRequest {
    type Error = MappingError;

    fn try_from(mut query_request: ProtobufQueryRequest) -> Result<Self, Self::Error> {
        let state_hash = {
            let state_hash = query_request.get_state_hash();
            let length = state_hash.len();
            if length != BLAKE2B_DIGEST_LENGTH {
                return Err(MappingError::InvalidStateHashLength {
                    expected: BLAKE2B_DIGEST_LENGTH,
                    actual: length,
                });
            }
            state_hash
                .try_into()
                .map_err(|_| MappingError::TryFromSliceError)?
        };

        let key = query_request
            .take_base_key()
            .try_into()
            .map_err(MappingError::ParsingError)?;

        let path = query_request.take_path().into_vec();

        Ok(QueryRequest::new(state_hash, key, path))
    }
}
