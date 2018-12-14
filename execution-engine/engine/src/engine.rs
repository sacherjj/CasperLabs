use core::marker::PhantomData;
use execution::{exec, Error as ExecutionError};
use parity_wasm::elements::Module;
use storage::{ExecutionEffect, GlobalState, TrackingCopy};
use wasm_prep::process;

// TODO placeholder for execution engine
pub struct EngineState<T: TrackingCopy, G: GlobalState<T>> {
    // In Michael's PoC state is an instance of TrackingCopy.
    // It tracks the "state" of the blockchain (or is an interface to it).
    // I think it should be constrained with a lifetime parameter.
    state: G,
    phantom: PhantomData<T>, //necessary to make the compiler not complain that I don't use T, even though G uses it.
}

#[derive(Debug)]
pub enum Error {
    PreprocessingError { error: String },
    SignatureError { error: String },
    ExecError(ExecutionError),
}

impl<T, G> EngineState<T, G>
where
    T: TrackingCopy,
    G: GlobalState<T>,
{
    pub fn new(state: G) -> EngineState<T, G> {
        EngineState {
            state,
            phantom: PhantomData,
        }
    }
    //TODO run_deploy should perform preprocessing and validation of the deploy.
    //It should validate the signatures, ocaps etc.
    pub fn run_deploy(
        &self,
        module_bytes: &[u8],
        address: [u8; 20],
    ) -> Result<ExecutionEffect, Error> {
        let module = self.preprocess_module(module_bytes)?;
        exec(module, address, &self.state).map_err(|e| Error::ExecError(e))
    }

    //TODO: inject gas counter, limit stack size etc
    fn preprocess_module(&self, module_bytes: &[u8]) -> Result<Module, Error> {
        process(module_bytes).map_err(|err_str| Error::PreprocessingError { error: err_str })
    }

    //TODO return proper error
    pub fn validate_signatures(
        &self,
        _deploy: &[u8],
        _signature: &[u8],
        _signature_alg: &str,
    ) -> Result<String, Error> {
        Ok(String::from("OK"))
    }
}
