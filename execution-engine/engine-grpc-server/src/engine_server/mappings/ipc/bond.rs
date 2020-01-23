use std::convert::{TryFrom, TryInto};

use types::{account::PublicKey, U512};

use crate::engine_server::{ipc::Bond, mappings::MappingError};

impl From<(PublicKey, U512)> for Bond {
    fn from((key, amount): (PublicKey, U512)) -> Self {
        let mut pb_bond = Bond::new();
        pb_bond.set_validator_public_key(key.to_vec());
        pb_bond.set_stake(amount.into());
        pb_bond
    }
}

impl TryFrom<Bond> for (PublicKey, U512) {
    type Error = MappingError;

    fn try_from(mut pb_bond: Bond) -> Result<Self, Self::Error> {
        // TODO: our TryFromSliceForPublicKeyError should convey length info
        let public_key = pb_bond.get_validator_public_key().try_into().map_err(|_| {
            MappingError::invalid_public_key_length(pb_bond.validator_public_key.len())
        })?;

        let stake = pb_bond.take_stake().try_into()?;

        Ok((public_key, stake))
    }
}

#[cfg(test)]
mod tests {
    use proptest::proptest;

    use types::gens;

    use super::*;
    use crate::engine_server::mappings::test_utils;

    proptest! {
        #[test]
        fn round_trip(public_key in gens::public_key_arb(), u512 in gens::u512_arb()) {
            test_utils::protobuf_round_trip::<(PublicKey, U512), Bond>((public_key, u512));
        }
    }
}
