use common::bytesrepr::Error as BytesReprError;

#[derive(Fail, Debug)]
pub enum GlobalStateError {
    #[fail(display = "{}", _0)]
    BytesReprError(BytesReprError),
}

impl From<BytesReprError> for GlobalStateError {
    fn from(error: BytesReprError) -> Self {
        GlobalStateError::BytesReprError(error)
    }
}
