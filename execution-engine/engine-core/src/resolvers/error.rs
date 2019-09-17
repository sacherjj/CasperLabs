use contract_ffi::value::ProtocolVersion;

#[derive(Debug)]
pub enum ResolverError {
    UnknownProtocolVersion(ProtocolVersion),
    NoImportedMemory,
}
