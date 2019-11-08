use contract_ffi::value::ProtocolVersion;

use crate::engine_server::state::ProtocolVersion as ProtobufProtocolVersion;

impl From<ProtocolVersion> for ProtobufProtocolVersion {
    fn from(protocol_version: ProtocolVersion) -> Self {
        let sem_ver = protocol_version.value();
        ProtobufProtocolVersion {
            major: sem_ver.major,
            minor: sem_ver.minor,
            patch: sem_ver.patch,
            ..Default::default()
        }
    }
}

impl From<ProtobufProtocolVersion> for ProtocolVersion {
    fn from(pb_protocol_version: ProtobufProtocolVersion) -> Self {
        ProtocolVersion::from_parts(
            pb_protocol_version.major,
            pb_protocol_version.minor,
            pb_protocol_version.patch,
        )
    }
}

#[cfg(test)]
mod tests {
    use proptest::{prelude::any, proptest};

    use super::*;
    use crate::engine_server::mappings::test_utils;

    proptest! {
        #[test]
        fn round_trip((major, minor, patch) in any::<(u32, u32, u32)>()) {
            let protocol_version = ProtocolVersion::from_parts(major, minor, patch);
            test_utils::protobuf_round_trip::<ProtocolVersion, ProtobufProtocolVersion>(
                protocol_version,
            );
        }
    }
}
