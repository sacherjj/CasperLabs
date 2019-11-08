use std::convert::TryFrom;

use engine_shared::transform::{Error as TransformError, TypeMismatch};

use crate::engine_server::{
    mappings::ParsingError,
    transforms::{
        TransformFailure as ProtobufTransformFailure,
        TransformFailure_oneof_failure_instance as ProtobufTransformFailureEnum,
        TypeMismatch as ProtobufTypeMismatch,
    },
};

impl From<TypeMismatch> for ProtobufTypeMismatch {
    fn from(type_mismatch: TypeMismatch) -> ProtobufTypeMismatch {
        let mut pb_type_mismatch = ProtobufTypeMismatch::new();
        pb_type_mismatch.set_expected(type_mismatch.expected);
        pb_type_mismatch.set_found(type_mismatch.found);
        pb_type_mismatch
    }
}

impl From<TransformError> for ProtobufTransformFailure {
    fn from(error: TransformError) -> Self {
        let mut pb_transform_failure = ProtobufTransformFailure::new();
        match error {
            TransformError::TypeMismatch(type_mismatch) => {
                pb_transform_failure.set_type_mismatch(type_mismatch.into())
            }
        }
        pb_transform_failure
    }
}

impl TryFrom<ProtobufTransformFailure> for TransformError {
    type Error = ParsingError;

    fn try_from(
        pb_transform_failure: ProtobufTransformFailure,
    ) -> Result<TransformError, ParsingError> {
        let pb_transform_failure = pb_transform_failure
            .failure_instance
            .ok_or_else(|| ParsingError::from("Unable to parse Protobuf TransformFailure"))?;
        match pb_transform_failure {
            ProtobufTransformFailureEnum::type_mismatch(ProtobufTypeMismatch {
                expected,
                found,
                ..
            }) => {
                let type_mismatch = TypeMismatch { expected, found };
                Ok(TransformError::TypeMismatch(type_mismatch))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine_server::mappings::test_utils;

    #[test]
    fn round_trip() {
        let error = TransformError::TypeMismatch(TypeMismatch::new(
            "expected".to_string(),
            "found".to_string(),
        ));
        test_utils::protobuf_round_trip::<TransformError, ProtobufTransformFailure>(error);
    }
}
