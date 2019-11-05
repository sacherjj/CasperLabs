use std::collections::BTreeSet;

use contract_ffi::value::account::PublicKey;

use crate::engine_state::executable_deploy_item::ExecutableDeployItem;
use crate::DeployHash;

type GasPrice = u64;

/// Represents a deploy to be executed.  Corresponds to the similarly-named ipc protobuf message.
pub struct DeployItem {
    address: PublicKey,
    session: ExecutableDeployItem,
    payment: ExecutableDeployItem,
    gas_price: GasPrice,
    authorization_keys: BTreeSet<PublicKey>,
    deploy_hash: DeployHash,
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
    pub fn address(&self) -> PublicKey {
        self.address
    }

    pub fn session(&self) -> &ExecutableDeployItem {
        &self.session
    }

    pub fn payment(&self) -> &ExecutableDeployItem {
        &self.payment
    }

    pub fn gas_price(&self) -> GasPrice {
        self.gas_price
    }

    pub fn authorization_keys(&self) -> &BTreeSet<PublicKey> {
        &self.authorization_keys
    }

    pub fn deploy_hash(&self) -> DeployHash {
        self.deploy_hash
    }
}
