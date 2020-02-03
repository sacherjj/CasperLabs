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
    pub fn new(
        mut left: AdditiveMap<Key, Transform>,
        mut right: AdditiveMap<Key, Transform>,
    ) -> Self {
        let mut both = AdditiveMap::new();
        for key in left.keys().copied().collect::<Vec<_>>() {
            let left_value = left.remove(&key).unwrap();
            if let Some(right_value) = right.remove(&key) {
                if left_value == right_value {
                    both.insert(key, left_value);
                } else {
                    left.insert(key, left_value);
                    right.insert(key, right_value);
                }
            } else {
                left.insert(key, left_value);
            }
        }

        AdditiveMapDiff { left, both, right }
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

#[cfg(test)]
mod tests {
    use lazy_static::lazy_static;
    use rand::{self, Rng};

    use super::*;

    const MIN_ELEMENTS: u8 = 1;
    const MAX_ELEMENTS: u8 = 10;

    lazy_static! {
        static ref LEFT_ONLY: AdditiveMap<Key, Transform> = {
            let mut map = AdditiveMap::new();
            for i in 0..random_element_count() {
                map.insert(Key::Local([i; 32]), Transform::AddInt32(i.into()));
            }
            map
        };
        static ref BOTH: AdditiveMap<Key, Transform> = {
            let mut map = AdditiveMap::new();
            for i in 0..random_element_count() {
                map.insert(Key::Local([i + MAX_ELEMENTS; 32]), Transform::Identity);
            }
            map
        };
        static ref RIGHT_ONLY: AdditiveMap<Key, Transform> = {
            let mut map = AdditiveMap::new();
            for i in 0..random_element_count() {
                map.insert(Key::Local([i; 32]), Transform::AddUInt512(i.into()));
            }
            map
        };
    }

    fn random_element_count() -> u8 {
        rand::thread_rng().gen_range(MIN_ELEMENTS, MAX_ELEMENTS + 1)
    }

    struct TestFixture {
        expected: AdditiveMapDiff,
    }

    impl TestFixture {
        fn new(
            left_only: AdditiveMap<Key, Transform>,
            both: AdditiveMap<Key, Transform>,
            right_only: AdditiveMap<Key, Transform>,
        ) -> Self {
            TestFixture {
                expected: AdditiveMapDiff {
                    left: left_only,
                    both,
                    right: right_only,
                },
            }
        }

        fn left(&self) -> AdditiveMap<Key, Transform> {
            self.expected
                .left
                .iter()
                .chain(self.expected.both.iter())
                .map(|(key, transform)| (*key, transform.clone()))
                .collect()
        }

        fn right(&self) -> AdditiveMap<Key, Transform> {
            self.expected
                .right
                .iter()
                .chain(self.expected.both.iter())
                .map(|(key, transform)| (*key, transform.clone()))
                .collect()
        }

        fn run(&self) {
            let diff = AdditiveMapDiff::new(self.left(), self.right());
            assert_eq!(self.expected, diff);
        }
    }

    #[test]
    fn should_create_diff_where_left_is_subset_of_right() {
        let fixture = TestFixture::new(AdditiveMap::new(), BOTH.clone(), RIGHT_ONLY.clone());
        fixture.run();
    }

    #[test]
    fn should_create_diff_where_right_is_subset_of_left() {
        let fixture = TestFixture::new(LEFT_ONLY.clone(), BOTH.clone(), AdditiveMap::new());
        fixture.run();
    }

    #[test]
    fn should_create_diff_where_no_intersection() {
        let fixture = TestFixture::new(LEFT_ONLY.clone(), AdditiveMap::new(), RIGHT_ONLY.clone());
        fixture.run();
    }

    #[test]
    fn should_create_diff_where_both_equal() {
        let fixture = TestFixture::new(AdditiveMap::new(), BOTH.clone(), AdditiveMap::new());
        fixture.run();
    }

    #[test]
    fn should_create_diff_where_left_is_empty() {
        let fixture = TestFixture::new(AdditiveMap::new(), AdditiveMap::new(), RIGHT_ONLY.clone());
        fixture.run();
    }

    #[test]
    fn should_create_diff_where_right_is_empty() {
        let fixture = TestFixture::new(LEFT_ONLY.clone(), AdditiveMap::new(), AdditiveMap::new());
        fixture.run();
    }

    #[test]
    fn should_create_diff_where_both_are_empty() {
        let fixture = TestFixture::new(AdditiveMap::new(), AdditiveMap::new(), AdditiveMap::new());
        fixture.run();
    }
}
