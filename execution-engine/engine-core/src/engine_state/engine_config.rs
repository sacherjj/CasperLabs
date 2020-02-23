/// The runtime configuration of the execution engine
#[derive(Debug, Copy, Clone)]
pub struct EngineConfig {
    // feature flags go here
    turbo: bool,
}

impl EngineConfig {
    /// Creates a new engine configuration with default parameters.
    pub fn new() -> EngineConfig {
        Default::default()
    }

    pub fn turbo(self) -> bool {
        self.turbo
    }

    pub fn with_turbo(&mut self, turbo: bool) -> &mut EngineConfig {
        self.turbo = turbo;
        self
    }
}

impl Default for EngineConfig {
    fn default() -> Self {
        EngineConfig { turbo: false }
    }
}
