use failure::Fail;

use shared::newtypes::Blake2bHash;

use execution;

#[derive(Fail, Debug)]
pub enum Error {
    #[fail(display = "Invalid public key length: expected {}, actual {}", _0, _1)]
    InvalidPublicKeyLength { expected: usize, actual: usize },
    #[fail(display = "Wasm preprocessing error: {:?}", _0)]
    WasmPreprocessingError(wasm_prep::PreprocessingError),
    #[fail(display = "Wasm serialization error: {:?}", _0)]
    WasmSerializationError(parity_wasm::SerializationError),
    #[fail(display = "Execution error: {}", _0)]
    ExecError(::execution::Error),
    #[fail(display = "Storage error: {}", _0)]
    StorageError(storage::error::Error),
    #[fail(display = "Authorization failure: not authorized.")]
    AuthorizationError,
}

impl From<wasm_prep::PreprocessingError> for Error {
    fn from(error: wasm_prep::PreprocessingError) -> Self {
        Error::WasmPreprocessingError(error)
    }
}

impl From<parity_wasm::SerializationError> for Error {
    fn from(error: parity_wasm::SerializationError) -> Self {
        Error::WasmSerializationError(error)
    }
}

impl From<execution::Error> for Error {
    fn from(error: execution::Error) -> Self {
        Error::ExecError(error)
    }
}

impl From<storage::error::Error> for Error {
    fn from(error: storage::error::Error) -> Self {
        Error::StorageError(error)
    }
}

impl From<!> for Error {
    fn from(error: !) -> Self {
        match error {}
    }
}

#[derive(Debug, PartialEq, Eq, Clone)]
pub struct RootNotFound(pub Blake2bHash);
