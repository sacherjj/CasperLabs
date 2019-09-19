pub mod error;
pub mod memory_resolver;
pub mod v1_function_index;
mod v1_resolver;

use contract_ffi::value::ProtocolVersion;
use wasmi::ModuleImportResolver;

use self::error::ResolverError;
use crate::resolvers::memory_resolver::MemoryResolver;

/// Creates a module resolver for given protocol version.
///
/// * `protocol_version` Version of the protocol. Can't be lower than 1.
pub fn create_module_resolver(
    protocol_version: ProtocolVersion,
) -> Result<impl ModuleImportResolver + MemoryResolver, ResolverError> {
    if protocol_version == ProtocolVersion::new(1) {
        return Ok(v1_resolver::RuntimeModuleImportResolver::default());
    }
    Err(ResolverError::UnknownProtocolVersion(protocol_version))
}

#[test]
fn resolve_invalid_module() {
    assert!(create_module_resolver(ProtocolVersion::default()).is_err());
}

#[test]
fn protocol_version_1_always_resolves() {
    assert!(create_module_resolver(ProtocolVersion::new(1)).is_ok());
}
