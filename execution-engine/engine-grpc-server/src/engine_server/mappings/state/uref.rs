use std::convert::TryFrom;

use contract_ffi::uref::{AccessRights, URef};

use crate::engine_server::{
    mappings::{self, ParsingError},
    state::{Key_URef as ProtobufURef, Key_URef_AccessRights as ProtobufAccessRights},
};

impl From<AccessRights> for ProtobufAccessRights {
    fn from(access_rights: AccessRights) -> Self {
        match access_rights {
            AccessRights::READ => ProtobufAccessRights::READ,
            AccessRights::WRITE => ProtobufAccessRights::WRITE,
            AccessRights::ADD => ProtobufAccessRights::ADD,
            AccessRights::READ_ADD => ProtobufAccessRights::READ_ADD,
            AccessRights::READ_WRITE => ProtobufAccessRights::READ_WRITE,
            AccessRights::ADD_WRITE => ProtobufAccessRights::ADD_WRITE,
            AccessRights::READ_ADD_WRITE => ProtobufAccessRights::READ_ADD_WRITE,
            _ => ProtobufAccessRights::UNKNOWN,
        }
    }
}

impl From<URef> for ProtobufURef {
    fn from(uref: URef) -> Self {
        let mut pb_uref = ProtobufURef::new();
        pb_uref.set_uref(uref.addr().to_vec());
        if let Some(access_rights) = uref.access_rights() {
            pb_uref.set_access_rights(access_rights.into());
        }
        pb_uref
    }
}

impl TryFrom<ProtobufURef> for URef {
    type Error = ParsingError;

    fn try_from(pb_uref: ProtobufURef) -> Result<Self, Self::Error> {
        let addr = mappings::vec_to_array(pb_uref.uref, "Protobuf URef addr")?;

        let maybe_access_rights = match pb_uref.access_rights {
            ProtobufAccessRights::UNKNOWN => None,
            ProtobufAccessRights::READ => Some(AccessRights::READ),
            ProtobufAccessRights::WRITE => Some(AccessRights::WRITE),
            ProtobufAccessRights::ADD => Some(AccessRights::ADD),
            ProtobufAccessRights::READ_ADD => Some(AccessRights::READ_ADD),
            ProtobufAccessRights::READ_WRITE => Some(AccessRights::READ_WRITE),
            ProtobufAccessRights::ADD_WRITE => Some(AccessRights::ADD_WRITE),
            ProtobufAccessRights::READ_ADD_WRITE => Some(AccessRights::READ_ADD_WRITE),
        };

        let uref = match maybe_access_rights {
            Some(access_rights) => URef::new(addr, access_rights),
            None => URef::new(addr, AccessRights::READ).remove_access_rights(),
        };

        Ok(uref)
    }
}

#[cfg(test)]
mod tests {
    use rand;

    use contract_ffi::uref::UREF_ADDR_SIZE;

    use super::*;
    use crate::engine_server::mappings::test_utils;

    #[test]
    fn round_trip() {
        for access_rights in &[
            AccessRights::READ,
            AccessRights::WRITE,
            AccessRights::ADD,
            AccessRights::READ_ADD,
            AccessRights::READ_WRITE,
            AccessRights::ADD_WRITE,
            AccessRights::READ_ADD_WRITE,
        ] {
            let uref = URef::new(rand::random(), *access_rights);
            test_utils::protobuf_round_trip::<URef, ProtobufURef>(uref);
        }

        let uref = URef::new(rand::random(), AccessRights::READ).remove_access_rights();
        test_utils::protobuf_round_trip::<URef, ProtobufURef>(uref);
    }

    #[test]
    fn should_fail_to_parse() {
        // Check we handle invalid Protobuf URefs correctly.
        let empty_pb_uref = ProtobufURef::new();
        assert!(URef::try_from(empty_pb_uref).is_err());

        let mut pb_uref_invalid_addr = ProtobufURef::new();
        pb_uref_invalid_addr.set_uref(vec![1; UREF_ADDR_SIZE - 1]);
        assert!(URef::try_from(pb_uref_invalid_addr).is_err());

        // Check Protobuf URef with `AccessRights::UNKNOWN` parses to a URef with no access rights.
        let addr: [u8; UREF_ADDR_SIZE] = rand::random();
        let mut pb_uref = ProtobufURef::new();
        pb_uref.set_uref(addr.to_vec());
        pb_uref.set_access_rights(ProtobufAccessRights::UNKNOWN);
        let parsed_uref = URef::try_from(pb_uref).unwrap();
        assert_eq!(addr, parsed_uref.addr());
        assert!(parsed_uref.access_rights().is_none());
    }
}
