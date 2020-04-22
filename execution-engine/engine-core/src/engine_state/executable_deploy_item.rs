use types::{EntryPointType, SemVer};

#[derive(Clone, PartialEq, Eq)]
pub enum ExecutableDeployItem {
    ModuleBytes {
        module_bytes: Vec<u8>,
        // assumes implicit `call` noarg entrypoint
        args: Vec<u8>,
    },
    StoredContractByHash {
        hash: Vec<u8>,
        // TODO: add entrypoint -> once local key is replaced with hash, add_contract_version could
        // return hash
        args: Vec<u8>,
    },
    StoredContractByName {
        name: String,
        // TODO: add entrypoint name
        args: Vec<u8>,
    },
    StoredContractByURef {
        uref: Vec<u8>,
        args: Vec<u8>,
    },
    StoredVersionedContractByName {
        name: String,        // named key for metadata?
        version: SemVer,     // finds active version
        entry_point: String, // finds header by entrypoint name
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
            ExecutableDeployItem::StoredVersionedContractByName { args, .. } => args,
        }
    }

    pub fn entry_point_name(&self) -> &str {
        match self {
            ExecutableDeployItem::StoredVersionedContractByName { entry_point, .. } => &entry_point,
            _ => "call",
        }
    }
}
