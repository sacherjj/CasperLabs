use types::ProtocolVersion;

#[derive(Debug)]
pub enum ResolverError {
    UnknownProtocolVersion(ProtocolVersion),
    NoImportedMemory,
}
