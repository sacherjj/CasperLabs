#[derive(Debug)]
pub enum ResolverError {
    UnknownProtocolVersion(u64),
    NoImportedMemory,
}
