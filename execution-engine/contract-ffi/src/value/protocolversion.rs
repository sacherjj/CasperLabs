use uint::core_::fmt;

#[derive(Debug, Default, Copy, Clone, Eq, PartialEq, Ord, PartialOrd)]
pub struct ProtocolVersion(u64);

impl ProtocolVersion {
    pub fn new(version: u64) -> ProtocolVersion {
        ProtocolVersion(version)
    }

    #[allow(clippy::trivially_copy_pass_by_ref)] //TODO: remove attr after switch to SemVer
    pub fn value(&self) -> u64 {
        self.0
    }
}

impl fmt::Display for ProtocolVersion {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self.0)
    }
}

#[cfg(test)]
mod tests {
    use crate::value::ProtocolVersion;

    #[test]
    fn should_be_able_to_get_instance() {
        let initial_value = 1;
        let item = ProtocolVersion::new(initial_value);
        assert_eq!(initial_value, item.value(), "should have equal value")
    }

    #[test]
    fn should_be_able_to_compare_two_instances() {
        let lhs = ProtocolVersion::new(1);
        let rhs = ProtocolVersion::new(1);
        assert_eq!(lhs, rhs, "should be equal");
        let rhs = ProtocolVersion::new(2);
        assert_ne!(lhs, rhs, "should not be equal")
    }

    #[test]
    fn should_be_able_to_default() {
        let defaulted = ProtocolVersion::default();
        let expected = ProtocolVersion::new(0);
        assert_eq!(defaulted, expected, "should be equal")
    }

    #[test]
    fn should_be_able_to_compare_relative_value() {
        let lhs = ProtocolVersion::new(2);
        let rhs = ProtocolVersion::new(1);
        assert!(lhs > rhs, "should be gt");
        let rhs = ProtocolVersion::new(2);
        assert!(lhs >= rhs, "should be gte");
        assert!(lhs <= rhs, "should be lte");
        let lhs = ProtocolVersion::new(1);
        assert!(lhs < rhs, "should be lt");
    }
}
