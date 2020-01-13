use contract::value::ProtocolVersion;

#[derive(Debug)]
pub enum ResolverError {
    UnknownProtocolVersion(ProtocolVersion),
    NoImportedMemory,
}
