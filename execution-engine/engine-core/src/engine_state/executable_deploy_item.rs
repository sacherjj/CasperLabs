#[derive(Clone, PartialEq, Eq)]
pub enum ExecutableDeployItem {
    ModuleBytes {
        module_bytes: Vec<u8>,
        args: Vec<u8>,
    },
    StoredContractByHash {
        hash: Vec<u8>,
        args: Vec<u8>,
    },
    StoredContractByName {
        name: String,
        args: Vec<u8>,
    },
    StoredContractByURef {
        uref: Vec<u8>,
        args: Vec<u8>,
    },
}

impl ExecutableDeployItem {
    pub fn take_args(self) -> Vec<u8> {
        match self {
            ExecutableDeployItem::ModuleBytes { args, .. } => args,
            ExecutableDeployItem::StoredContractByHash { args, .. } => args,
            ExecutableDeployItem::StoredContractByName { args, .. } => args,
            ExecutableDeployItem::StoredContractByURef { args, .. } => args,
        }
    }
}
