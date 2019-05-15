// Taken (partially) from parity-ethereum
#[derive(Debug)]
pub struct WasmCosts {
    /// Default opcode cost
    pub regular: u32,
    /// Div operations multiplier.
    pub div: u32,
    /// Mul operations multiplier.
    pub mul: u32,
    /// Memory (load/store) operations multiplier.
    pub mem: u32,
    /// Memory stipend. Amount of free memory (in 64kb pages) each contract can use for stack.
    pub initial_mem: u32,
    /// Grow memory cost, per page (64kb)
    pub grow_mem: u32,
    /// Memory copy cost, per byte
    pub memcpy: u32,
    /// Max stack height (native WebAssembly stack limiter)
    pub max_stack_height: u32,
    /// Cost of wasm opcode is calculated as TABLE_ENTRY_COST * `opcodes_mul` / `opcodes_div`
    pub opcodes_mul: u32,
    /// Cost of wasm opcode is calculated as TABLE_ENTRY_COST * `opcodes_mul` / `opcodes_div`
    pub opcodes_div: u32,
}

impl WasmCosts {
    pub fn from_version(protocol_version: u64) -> Option<WasmCosts> {
        match protocol_version {
            1 => Some(WasmCosts {
                regular: 1,
                div: 16,
                mul: 4,
                mem: 2,
                initial_mem: 4096,
                grow_mem: 8192,
                memcpy: 1,
                max_stack_height: 64 * 1024,
                opcodes_mul: 3,
                opcodes_div: 8,
            }),
            _ => None,
        }
    }
}
