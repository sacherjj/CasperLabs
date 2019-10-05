use core::cmp::Ordering;
use uint::core_::fmt;

use super::SemVer;

#[derive(Debug, Default, Copy, Clone, Eq, PartialEq, Ord)]
pub struct ProtocolVersion(SemVer);

impl ProtocolVersion {
    pub const V1_0_0: ProtocolVersion = ProtocolVersion(SemVer {
        major: 1,
        minor: 0,
        patch: 0,
    });

    pub fn new(version: SemVer) -> ProtocolVersion {
        ProtocolVersion(version)
    }

    pub fn from_parts(major: u32, minor: u32, patch: u32) -> ProtocolVersion {
        let sem_ver = SemVer::new(major, minor, patch);
        Self::new(sem_ver)
    }

    #[allow(clippy::trivially_copy_pass_by_ref)] //TODO: remove attr after switch to SemVer
    pub fn value(&self) -> SemVer {
        self.0
    }
}

impl fmt::Display for ProtocolVersion {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        self.0.fmt(f)
    }
}

impl PartialOrd for ProtocolVersion {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.value().cmp(&other.value()))
    }
}

#[cfg(test)]
mod tests {
    use crate::value::semver::SemVer;
    use crate::value::ProtocolVersion;

    #[test]
    fn should_be_able_to_get_instance() {
        let initial_value = SemVer::new(1, 0, 0);
        let item = ProtocolVersion::new(initial_value);
        assert_eq!(initial_value, item.value(), "should have equal value")
    }

    #[test]
    fn should_be_able_to_compare_two_instances() {
        let lhs = ProtocolVersion::new(SemVer::new(1, 0, 0));
        let rhs = ProtocolVersion::new(SemVer::new(1, 0, 0));
        assert_eq!(lhs, rhs, "should be equal");
        let rhs = ProtocolVersion::new(SemVer::new(2, 0, 0));
        assert_ne!(lhs, rhs, "should not be equal")
    }

    #[test]
    fn should_be_able_to_default() {
        let defaulted = ProtocolVersion::default();
        let expected = ProtocolVersion::new(SemVer::new(0, 0, 0));
        assert_eq!(defaulted, expected, "should be equal")
    }

    #[test]
    fn should_be_able_to_compare_relative_value() {
        let lhs = ProtocolVersion::new(SemVer::new(2, 0, 0));
        let rhs = ProtocolVersion::new(SemVer::new(1, 0, 0));
        assert!(lhs > rhs, "should be gt");
        let rhs = ProtocolVersion::new(SemVer::new(2, 0, 0));
        assert!(lhs >= rhs, "should be gte");
        assert!(lhs <= rhs, "should be lte");
        let lhs = ProtocolVersion::new(SemVer::new(1, 0, 0));
        assert!(lhs < rhs, "should be lt");
    }
}
