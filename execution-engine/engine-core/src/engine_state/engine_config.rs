/// The runtime configuration of the execution engine
#[derive(Debug, Clone)]
pub struct EngineConfig {
    use_payment_code: bool,
}

impl EngineConfig {
    /// Creates a new engine configuration with default parameters.
    pub fn new() -> EngineConfig {
        Default::default()
    }

    /// Sets the `use_payment_code` field to the given arg.
    pub fn set_use_payment_code(mut self, arg: bool) -> EngineConfig {
        self.use_payment_code = arg;
        self
    }

    pub fn use_payment_code(&self) -> bool {
        self.use_payment_code
    }
}

impl Default for EngineConfig {
    fn default() -> Self {
        EngineConfig {
            use_payment_code: false,
        }
    }
}
