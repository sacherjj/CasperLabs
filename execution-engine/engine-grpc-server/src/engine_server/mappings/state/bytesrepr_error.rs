use contract_ffi::bytesrepr::Error;

use crate::engine_server::state::BytesReprError;

impl From<Error> for BytesReprError {
    fn from(error: Error) -> Self {
        match error {
            Error::EarlyEndOfStream => BytesReprError::EARLY_END_OF_STREAM,
            Error::FormattingError => BytesReprError::FORMATTING_ERROR,
            Error::LeftOverBytes => BytesReprError::LEFT_OVER_BYTES,
            Error::OutOfMemoryError => BytesReprError::OUT_OF_MEMORY_ERROR,
        }
    }
}

impl From<BytesReprError> for Error {
    fn from(pb_error: BytesReprError) -> Self {
        match pb_error {
            BytesReprError::EARLY_END_OF_STREAM => Error::EarlyEndOfStream,
            BytesReprError::FORMATTING_ERROR => Error::FormattingError,
            BytesReprError::LEFT_OVER_BYTES => Error::LeftOverBytes,
            BytesReprError::OUT_OF_MEMORY_ERROR => Error::OutOfMemoryError,
        }
    }
}
