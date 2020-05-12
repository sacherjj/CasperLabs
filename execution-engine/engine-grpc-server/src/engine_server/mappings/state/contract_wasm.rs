use crate::engine_server::state;
use engine_shared::contract::ContractWasm;

impl From<ContractWasm> for state::ContractWasm {
    fn from(contract: ContractWasm) -> Self {
        let mut pb_contract_wasm = state::ContractWasm::new();
        pb_contract_wasm.set_wasm(contract.take_bytes());
        pb_contract_wasm
    }
}

impl From<state::ContractWasm> for ContractWasm {
    fn from(mut contract: state::ContractWasm) -> Self {
        ContractWasm::new(contract.take_wasm())
    }
}
