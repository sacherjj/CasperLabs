use std::collections::BTreeSet;

use types::account::PublicKey;

use crate::{engine_state::executable_deploy_item::ExecutableDeployItem, DeployHash};

type GasPrice = u64;

/// Represents a deploy to be executed.  Corresponds to the similarly-named ipc protobuf message.
#[derive(Clone, PartialEq, Eq)]
pub struct DeployItem {
    pub address: PublicKey,
    pub session: ExecutableDeployItem,
    pub payment: ExecutableDeployItem,
    pub gas_price: GasPrice,
    pub authorization_keys: BTreeSet<PublicKey>,
    pub deploy_hash: DeployHash,
}

impl DeployItem {
    /// Creates a [`DeployItem`].
    pub fn new(
        address: PublicKey,
        session: ExecutableDeployItem,
        payment: ExecutableDeployItem,
        gas_price: GasPrice,
        authorization_keys: BTreeSet<PublicKey>,
        deploy_hash: DeployHash,
    ) -> Self {
        DeployItem {
            address,
            session,
            payment,
            gas_price,
            authorization_keys,
            deploy_hash,
        }
    }
}
