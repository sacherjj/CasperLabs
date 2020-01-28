use engine_grpc_server::engine_server::{
    ipc::{
        ChainSpec_ActivationPoint, ChainSpec_CostTable_WasmCosts, ChainSpec_UpgradePoint,
        DeployCode, UpgradeRequest,
    },
    state,
};
use engine_wasm_prep::wasm_costs::WasmCosts;
use types::ProtocolVersion;

pub struct UpgradeRequestBuilder {
    pre_state_hash: Vec<u8>,
    current_protocol_version: state::ProtocolVersion,
    new_protocol_version: state::ProtocolVersion,
    upgrade_installer: DeployCode,
    new_costs: Option<ChainSpec_CostTable_WasmCosts>,
    activation_point: ChainSpec_ActivationPoint,
}

impl UpgradeRequestBuilder {
    pub fn new() -> Self {
        Default::default()
    }

    pub fn with_pre_state_hash(mut self, pre_state_hash: &[u8]) -> Self {
        self.pre_state_hash = pre_state_hash.to_vec();
        self
    }

    pub fn with_current_protocol_version(mut self, protocol_version: ProtocolVersion) -> Self {
        self.current_protocol_version = protocol_version.into();
        self
    }

    pub fn with_new_protocol_version(mut self, protocol_version: ProtocolVersion) -> Self {
        self.new_protocol_version = protocol_version.into();
        self
    }

    pub fn with_installer_code(mut self, upgrade_installer: DeployCode) -> Self {
        self.upgrade_installer = upgrade_installer;
        self
    }

    pub fn with_new_costs(mut self, wasm_costs: WasmCosts) -> Self {
        let mut new_costs = ChainSpec_CostTable_WasmCosts::new();
        new_costs.set_regular(wasm_costs.regular);
        new_costs.set_opcodes_mul(wasm_costs.opcodes_mul);
        new_costs.set_opcodes_div(wasm_costs.opcodes_div);
        new_costs.set_mul(wasm_costs.mul);
        new_costs.set_div(wasm_costs.div);
        new_costs.set_grow_mem(wasm_costs.grow_mem);
        new_costs.set_initial_mem(wasm_costs.initial_mem);
        new_costs.set_max_stack_height(wasm_costs.max_stack_height);
        new_costs.set_mem(wasm_costs.mem);
        new_costs.set_memcpy(wasm_costs.memcpy);
        self.new_costs = Some(new_costs);
        self
    }

    pub fn with_activation_point(mut self, rank: u64) -> Self {
        self.activation_point = {
            let mut ret = ChainSpec_ActivationPoint::new();
            ret.set_rank(rank);
            ret
        };
        self
    }

    pub fn build(self) -> UpgradeRequest {
        let mut upgrade_point = ChainSpec_UpgradePoint::new();
        upgrade_point.set_activation_point(self.activation_point);
        match self.new_costs {
            None => {}
            Some(new_costs) => {
                let mut cost_table =
                    engine_grpc_server::engine_server::ipc::ChainSpec_CostTable::new();
                cost_table.set_wasm(new_costs);
                upgrade_point.set_new_costs(cost_table);
            }
        }
        upgrade_point.set_protocol_version(self.new_protocol_version);
        upgrade_point.set_upgrade_installer(self.upgrade_installer);

        let mut upgrade_request = UpgradeRequest::new();
        upgrade_request.set_protocol_version(self.current_protocol_version);
        upgrade_request.set_upgrade_point(upgrade_point);
        upgrade_request
    }
}

impl Default for UpgradeRequestBuilder {
    fn default() -> Self {
        UpgradeRequestBuilder {
            pre_state_hash: Default::default(),
            current_protocol_version: Default::default(),
            new_protocol_version: Default::default(),
            upgrade_installer: Default::default(),
            new_costs: None,
            activation_point: Default::default(),
        }
    }
}
