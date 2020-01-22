use engine_shared::{additive_map::AdditiveMap, transform::Transform};
use types::Key;

/// Represents the difference between two `AdditiveMap`s.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct AdditiveMapDiff {
    left: AdditiveMap<Key, Transform>,
    both: AdditiveMap<Key, Transform>,
    right: AdditiveMap<Key, Transform>,
}

impl AdditiveMapDiff {
    /// Creates a diff from two `AdditiveMap`s.
    pub fn new(left: AdditiveMap<Key, Transform>, right: AdditiveMap<Key, Transform>) -> Self {
        let both = Default::default();
        let left_clone = left.clone();
        let mut ret = AdditiveMapDiff { left, both, right };

        for key in left_clone.keys() {
            let l = ret.left.remove_entry(key);
            let r = ret.right.remove_entry(key);

            match (l, r) {
                (Some(le), Some(re)) => {
                    if le == re {
                        ret.both.insert(*key, re.1);
                    } else {
                        ret.left.insert(*key, le.1);
                        ret.right.insert(*key, re.1);
                    }
                }
                (None, Some(re)) => {
                    ret.right.insert(*key, re.1);
                }
                (Some(le), None) => {
                    ret.left.insert(*key, le.1);
                }
                (None, None) => unreachable!(),
            }
        }

        ret
    }

    /// Returns the entries that are unique to the `left` input.
    pub fn left(&self) -> &AdditiveMap<Key, Transform> {
        &self.left
    }

    /// Returns the entries that are unique to the `right` input.
    pub fn right(&self) -> &AdditiveMap<Key, Transform> {
        &self.right
    }

    /// Returns the entries shared by both inputs.
    pub fn both(&self) -> &AdditiveMap<Key, Transform> {
        &self.both
    }
}
