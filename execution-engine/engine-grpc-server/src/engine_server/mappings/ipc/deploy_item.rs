use std::{
    collections::BTreeSet,
    convert::{TryFrom, TryInto},
};

use engine_core::engine_state::deploy_item::DeployItem;
use types::account::PublicKey;

use crate::engine_server::{ipc, mappings::MappingError};

impl TryFrom<ipc::DeployItem> for DeployItem {
    type Error = MappingError;

    fn try_from(mut pb_deploy_item: ipc::DeployItem) -> Result<Self, Self::Error> {
        let address = pb_deploy_item
            .get_address()
            .try_into()
            .map_err(|_| MappingError::invalid_public_key_length(pb_deploy_item.address.len()))?;

        let session = pb_deploy_item
            .take_session()
            .payload
            .map(Into::into)
            .ok_or_else(|| MappingError::MissingPayload)?;

        let payment = pb_deploy_item
            .take_payment()
            .payload
            .map(Into::into)
            .ok_or_else(|| MappingError::MissingPayload)?;

        let gas_price = pb_deploy_item.get_gas_price();

        let authorization_keys = pb_deploy_item
            .get_authorization_keys()
            .iter()
            .map(|raw: &Vec<u8>| {
                raw.as_slice()
                    .try_into()
                    .map_err(|_| MappingError::invalid_public_key_length(raw.len()))
            })
            .collect::<Result<BTreeSet<PublicKey>, Self::Error>>()?;

        let deploy_hash = pb_deploy_item.get_deploy_hash().try_into().map_err(|_| {
            MappingError::invalid_deploy_hash_length(pb_deploy_item.deploy_hash.len())
        })?;

        Ok(DeployItem::new(
            address,
            session,
            payment,
            gas_price,
            authorization_keys,
            deploy_hash,
        ))
    }
}
