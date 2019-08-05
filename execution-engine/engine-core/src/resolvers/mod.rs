pub mod error;
pub mod memory_resolver;
mod resolver_v1;

use wasmi::ModuleImportResolver;

use self::error::ResolverError;
use resolvers::memory_resolver::MemoryResolver;

/// Creates a module resolver for given protocol version.
///
/// * `protocol_version` Version of the protocol. Can't be lower than 1.
pub fn create_module_resolver(
    protocol_version: u64,
) -> Result<impl ModuleImportResolver + MemoryResolver, ResolverError> {
    match protocol_version {
        1 => Ok(resolver_v1::RuntimeModuleImportResolver::default()),
        _ => Err(ResolverError::UnknownProtocolVersion(protocol_version)),
    }
}

#[test]
fn resolve_invalid_module() {
    assert!(create_module_resolver(0).is_err());
}

#[test]
fn protocol_version_1_always_resolves() {
    assert!(create_module_resolver(1).is_ok());
}
