use failure::Fail;

use engine_shared::newtypes::Blake2bHash;
use types::{bytesrepr, system_contract_errors::mint};

use crate::execution;
use types::ProtocolVersion;

#[derive(Fail, Debug)]
pub enum Error {
    #[fail(display = "Invalid hash length: expected {}, actual {}", _0, _1)]
    InvalidHashLength { expected: usize, actual: usize },
    #[fail(display = "Invalid public key length: expected {}, actual {}", _0, _1)]
    InvalidPublicKeyLength { expected: usize, actual: usize },
    #[fail(display = "Invalid protocol version: {}", _0)]
    InvalidProtocolVersion(ProtocolVersion),
    #[fail(display = "Invalid upgrade config")]
    InvalidUpgradeConfig,
    #[fail(display = "Wasm preprocessing error: {}", _0)]
    WasmPreprocessing(engine_wasm_prep::PreprocessingError),
    #[fail(display = "Wasm serialization error: {:?}", _0)]
    WasmSerialization(parity_wasm::SerializationError),
    #[fail(display = "{}", _0)]
    Exec(execution::Error),
    #[fail(display = "Storage error: {}", _0)]
    Storage(engine_storage::error::Error),
    #[fail(display = "Authorization failure: not authorized.")]
    Authorization,
    #[fail(display = "Insufficient payment")]
    InsufficientPayment,
    #[fail(display = "Deploy error")]
    Deploy,
    #[fail(display = "Payment finalization error")]
    Finalization,
    #[fail(display = "Missing system contract association: {}", _0)]
    MissingSystemContract(String),
    #[fail(display = "Serialization error: {}", _0)]
    Serialization(bytesrepr::Error),
    #[fail(display = "Mint error: {}", _0)]
    Mint(mint::Error),
}

impl From<engine_wasm_prep::PreprocessingError> for Error {
    fn from(error: engine_wasm_prep::PreprocessingError) -> Self {
        Error::WasmPreprocessing(error)
    }
}

impl From<parity_wasm::SerializationError> for Error {
    fn from(error: parity_wasm::SerializationError) -> Self {
        Error::WasmSerialization(error)
    }
}

impl From<execution::Error> for Error {
    fn from(error: execution::Error) -> Self {
        Error::Exec(error)
    }
}

impl From<engine_storage::error::Error> for Error {
    fn from(error: engine_storage::error::Error) -> Self {
        Error::Storage(error)
    }
}

impl From<bytesrepr::Error> for Error {
    fn from(error: bytesrepr::Error) -> Self {
        Error::Serialization(error)
    }
}

impl From<mint::Error> for Error {
    fn from(error: mint::Error) -> Self {
        Error::Mint(error)
    }
}

impl From<!> for Error {
    fn from(error: !) -> Self {
        match error {}
    }
}

#[derive(Debug, PartialEq, Eq, Clone)]
pub struct RootNotFound(Blake2bHash);

impl RootNotFound {
    pub fn new(hash: Blake2bHash) -> Self {
        RootNotFound(hash)
    }

    pub fn to_vec(&self) -> Vec<u8> {
        self.0.to_vec()
    }
}
