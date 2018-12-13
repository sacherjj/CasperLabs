// TODO placeholder for execution engine
pub struct EngineState<T> {
    // In Michael's PoC state is an instance of TrackingCopy.
    // It tracks the "state" of the blockchain (or is an interface to it).
    // I think it should be constrained with a lifetime parameter.
    state: T,
}

impl<T> EngineState<T> {
    pub fn new(state: T) -> EngineState<T> {
        EngineState {
            state: state
        }
    }
    //TODO run_deploy should perform preprocessing and validation of the deploy.
    //It should validate the signatures, ocaps etc.
    pub fn run_deploy(&self, deploy: &Vec<u8>) -> Result<String, String> {
        self.validate_signatures(deploy, &Vec::new(), "ed25519")?;
        self.preprocess_deploy(deploy)?;
        Ok(String::from("OK"))
    }

    //TODO return proper error or instance of Wasm module
    fn preprocess_deploy(&self, deploy: &Vec<u8>) -> Result<String, String> {
        Ok(String::from("OK"))
    }

    //TODO return proper error
    fn validate_signatures(&self, deploy: &Vec<u8>, signature: &Vec<u8>, signature_alg: &str) -> Result<String, String> {
        Ok(String::from("OK"))
    }
}
