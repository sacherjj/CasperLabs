use parity_wasm::elements::Serialize;

use wasm_prep::wasm_costs::WasmCosts;
use wasm_prep::{Preprocessor, WasmiPreprocessor};

use engine_state;

#[derive(Debug, Clone)]
pub struct WasmiBytes(Vec<u8>);

impl WasmiBytes {
    pub fn new(raw_bytes: &[u8], wasm_costs: WasmCosts) -> Result<Self, engine_state::Error> {
        let mut ret = vec![];
        let wasmi_preprocessor: WasmiPreprocessor = WasmiPreprocessor::new(wasm_costs);
        let module = wasmi_preprocessor.preprocess(raw_bytes)?;
        module.serialize(&mut ret)?;
        Ok(WasmiBytes(ret))
    }
}

impl Into<Vec<u8>> for WasmiBytes {
    fn into(self) -> Vec<u8> {
        self.0
    }
}
