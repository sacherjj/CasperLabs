use parity_wasm::elements::Module;
use wasm_prep::process;

#[derive(Debug)]
pub enum Error {
    PreprocessingError { error: String },
    SignatureError { error: String },
}

// TODO placeholder for execution engine
pub struct EngineState<T> {
    // In Michael's PoC state is an instance of TrackingCopy.
    // It tracks the "state" of the blockchain (or is an interface to it).
    // I think it should be constrained with a lifetime parameter.
    state: T,
}

impl<T> EngineState<T> {
    pub fn new(state: T) -> EngineState<T> {
        EngineState { state: state }
    }
    //TODO run_deploy should perform preprocessing and validation of the deploy.
    //It should validate the signatures, ocaps etc.
    pub fn run_deploy(&self, deploy: &[u8]) -> Result<String, Error> {
        self.validate_signatures(deploy, &Vec::new(), "ed25519")?;
        self.preprocess_module(deploy)?;
        Ok(String::from("OK"))
    }

    //TODO: inject gas counter, limit stack size etc
    fn preprocess_module(&self, module_bytes: &[u8]) -> Result<Module, Error> {
        process(module_bytes).map_err(|err_str| Error::PreprocessingError { error: err_str })
    }

    //TODO return proper error
    fn validate_signatures(
        &self,
        deploy: &[u8],
        signature: &[u8],
        signature_alg: &str,
    ) -> Result<String, Error> {
        Ok(String::from("OK"))
    }
}
