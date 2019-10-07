use core::cmp::Ordering;
use core::fmt;
use core::hash::{Hash, Hasher};

#[derive(Debug, Copy, Clone, Eq, Ord)]
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

impl Hash for SemVer {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.major.hash(state);
        self.minor.hash(state);
        self.patch.hash(state);
    }
}

impl Default for SemVer {
    fn default() -> Self {
        Self::new(0, 0, 0)
    }
}

impl PartialOrd for SemVer {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(&other))
    }
}

impl PartialEq for SemVer {
    fn eq(&self, other: &SemVer) -> bool {
        self.major.eq(&other.major) && self.minor.eq(&other.minor) && self.patch.eq(&other.patch)
    }
}
