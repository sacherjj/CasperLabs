use core::fmt;

#[derive(Copy, Clone, Debug, Default, Hash, PartialEq, Eq, PartialOrd, Ord)]
pub struct SemVer {
    pub major: u32,
    pub minor: u32,
    pub patch: u32,
}

impl SemVer {
    pub const V1_0_0: SemVer = SemVer {
        major: 1,
        minor: 0,
        patch: 0,
    };

    pub fn new(major: u32, minor: u32, patch: u32) -> SemVer {
        SemVer {
            major,
            minor,
            patch,
        }
    }
}

impl fmt::Display for SemVer {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{}.{}.{}", self.major, self.minor, self.patch)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn should_compare_semver_versions() {
        assert!(SemVer::new(0, 0, 0) < SemVer::new(1, 2, 3));
        assert!(SemVer::new(1, 1, 0) < SemVer::new(1, 2, 0));
        assert!(SemVer::new(1, 0, 0) < SemVer::new(1, 2, 0));
        assert!(SemVer::new(1, 0, 0) < SemVer::new(1, 2, 3));
        assert!(SemVer::new(1, 2, 0) < SemVer::new(1, 2, 3));
        assert!(SemVer::new(1, 2, 3) == SemVer::new(1, 2, 3));
        assert!(SemVer::new(1, 2, 3) >= SemVer::new(1, 2, 3));
        assert!(SemVer::new(1, 2, 3) <= SemVer::new(1, 2, 3));
        assert!(SemVer::new(2, 0, 0) >= SemVer::new(1, 99, 99));
        assert!(SemVer::new(2, 0, 0) > SemVer::new(1, 99, 99));
    }
}
