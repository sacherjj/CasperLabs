/// The runtime configuration of the execution engine
#[derive(Debug, Clone)]
pub struct EngineConfig {
    // feature flags go here
}

impl EngineConfig {
    /// Creates a new engine configuration with default parameters.
    pub fn new() -> EngineConfig {
        Default::default()
    }
}

impl Default for EngineConfig {
    fn default() -> Self {
        EngineConfig {}
    }
}
