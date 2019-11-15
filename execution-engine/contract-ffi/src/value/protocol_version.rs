use uint::core_::fmt;

use super::SemVer;

#[derive(Copy, Clone, Debug, Default, Hash, PartialEq, Eq, PartialOrd, Ord)]
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

    /// Checks installer upgrade activation point
    ///
    /// Possible return values:
    /// - Some(true) => Upgrade possible, installer bytes are required
    /// - Some(false) => Upgrade possible, installer bytes are optional
    /// - None => Upgrade not possible
    pub fn check_upgrade_point(&self, next: &ProtocolVersion) -> Option<bool> {
        // Follows rules as explained in 3.1.1.1.1.3

        if next.0.major > self.0.major + 1 {
            None // Protocol major versions should increase monotonically by 1.
        } else if next.0.major == self.0.major + 1 {
            // A major version increase resets both the minor and patch versions to ( 0.0 ).
            if next.0.minor == 0 && next.0.patch == 0 {
                Some(true)
            } else {
                None
            }
        } else if next.0.major == self.0.major {
            if next.0.minor > self.0.minor + 1 {
                None // Protocol minor versions should increase monotonically by 1 within the same
                     // major version.
            } else if next.0.minor == self.0.minor + 1 {
                // A minor version increase resets the patch version to ( 0 ).
                if next.0.patch == 0 {
                    Some(false)
                } else {
                    None
                }
            } else if next.0.minor == self.0.minor {
                // Protocol patch versions should increase monotonically.
                if next.0.patch > self.0.patch {
                    Some(false)
                } else {
                    None
                }
            } else {
                None // Protocol minor versions should not go backwards within the same major
                     // version.
            }
        } else {
            None // Protocol major versions should not go backwards.
        }
    }

    /// Check if given version can be followed
    pub fn check_follows(&self, next: &ProtocolVersion) -> bool {
        // The logic is basically the same as installer upgrade validation,
        // except only 'Some' case is the indicator.
        self.check_upgrade_point(next).is_some()
    }
}

impl fmt::Display for ProtocolVersion {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        self.0.fmt(f)
    }
}

#[cfg(test)]
mod tests {
    use crate::value::{semver::SemVer, ProtocolVersion};

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

    #[test]
    fn should_check_follows_major_version_upgrade() {
        // If the upgrade protocol version is lower than or the same as EE's current in-use protocol
        // version the upgrade is rejected and an error is returned; this includes the special case
        // of a defaulted protocol version ( 0.0.0 ).
        let prev = ProtocolVersion::new(SemVer::new(1, 0, 0));
        let next = ProtocolVersion::new(SemVer::new(2, 0, 0));
        assert!(prev.check_follows(&next));

        // Major version must not decrease ...
        assert!(!next.check_follows(&prev));

        // ... and may only increase by exactly 1 from the current major version.
        let prev = ProtocolVersion::new(SemVer::new(1, 0, 0));
        let next = ProtocolVersion::new(SemVer::new(3, 0, 0));
        assert!(!prev.check_follows(&next));
    }

    #[test]
    fn should_check_follows_minor_version_upgrade() {
        // [major version] may remain the same in the case of a minor or patch version increase.

        // Minor version must not decrease within the same major version
        let prev = ProtocolVersion::new(SemVer::new(1, 1, 0));
        let next = ProtocolVersion::new(SemVer::new(1, 2, 0));
        assert!(!next.check_follows(&prev));

        // and may only increase by exactly 1 from the current minor version. It may remain the same
        // in the case of a patch only increase.
        assert!(prev.check_follows(&next));

        let prev = ProtocolVersion::new(SemVer::new(1, 1, 0));
        let next = ProtocolVersion::new(SemVer::new(1, 3, 0));
        assert!(!prev.check_follows(&next)); // wrong - increases minor by 2 for same major

        // A minor version increase resets the patch version to ( 0 ).
        let prev = ProtocolVersion::new(SemVer::new(1, 1, 0));
        let next = ProtocolVersion::new(SemVer::new(1, 2, 1));
        assert!(!prev.check_follows(&next)); // wrong - patch version should be reset for minor
                                             // version increase
    }

    #[test]
    fn should_check_follows_major_resets_minor_and_patch() {
        // A major version increase resets both the minor and patch versions to ( 0.0 ).
        let prev = ProtocolVersion::new(SemVer::new(1, 0, 0));
        let next = ProtocolVersion::new(SemVer::new(2, 1, 0));
        assert!(!prev.check_follows(&next)); // wrong - major increase should reset minor

        let prev = ProtocolVersion::new(SemVer::new(1, 0, 0));
        let next = ProtocolVersion::new(SemVer::new(2, 0, 1));
        assert!(!prev.check_follows(&next)); // wrong - major increase should reset patch

        let prev = ProtocolVersion::new(SemVer::new(1, 0, 0));
        let next = ProtocolVersion::new(SemVer::new(2, 1, 1));
        assert!(!prev.check_follows(&next)); // wrong - major increase should reset minor and patch
    }

    #[test]
    fn should_check_follows_patch_version_increase() {
        // Patch version must not decrease or remain the same within the same major and minor
        // version pair, but may skip.

        let prev = ProtocolVersion::new(SemVer::new(1, 0, 1));
        let next = ProtocolVersion::new(SemVer::new(1, 0, 0));
        assert!(!prev.check_follows(&next)); // wrong - should increaes monotonically

        let prev = ProtocolVersion::new(SemVer::new(1, 0, 0));
        let next = ProtocolVersion::new(SemVer::new(1, 0, 1));
        assert!(prev.check_follows(&next));

        let prev = ProtocolVersion::new(SemVer::new(1, 0, 8));
        let next = ProtocolVersion::new(SemVer::new(1, 0, 42));
        assert!(prev.check_follows(&next));
    }

    #[test]
    fn should_allow_for_installer_upgrade() {
        let prev = ProtocolVersion::new(SemVer::new(1, 0, 0));

        // installer is optional for patch bump
        let next = ProtocolVersion::new(SemVer::new(1, 0, 1));
        assert_eq!(prev.check_upgrade_point(&next), Some(false));

        let next = ProtocolVersion::new(SemVer::new(1, 0, 123));
        assert_eq!(prev.check_upgrade_point(&next), Some(false));

        // installer is optional for major bump
        let next = ProtocolVersion::new(SemVer::new(1, 1, 0));
        assert_eq!(prev.check_upgrade_point(&next), Some(false));

        // minor can be updated only by 1
        let next = ProtocolVersion::new(SemVer::new(1, 2, 0));
        assert_eq!(prev.check_upgrade_point(&next), None);

        // no upgrade - minor resets patch
        let next = ProtocolVersion::new(SemVer::new(1, 1, 1));
        assert_eq!(prev.check_upgrade_point(&next), None);

        // major upgrade requires installer to be present
        let next = ProtocolVersion::new(SemVer::new(2, 0, 0));
        assert_eq!(prev.check_upgrade_point(&next), Some(true));

        // can bump only by 1
        let next = ProtocolVersion::new(SemVer::new(3, 0, 0));
        assert_eq!(prev.check_upgrade_point(&next), None);
    }
}
