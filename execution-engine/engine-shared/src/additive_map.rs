use std::collections::hash_map::{IntoIter, Iter, IterMut, RandomState};
use std::collections::HashMap;
use std::fmt::{self, Debug, Formatter};
use std::hash::{BuildHasher, Hash};
use std::iter::{FromIterator, IntoIterator};
use std::ops::{AddAssign, Deref, DerefMut};

#[derive(Clone)]
pub struct AdditiveMap<K, V, S = RandomState>(HashMap<K, V, S>);

impl<K: Eq + Hash, V> AdditiveMap<K, V, RandomState> {
    pub fn new() -> Self {
        Self(Default::default())
    }
}

impl<K, V, S> Deref for AdditiveMap<K, V, S> {
    type Target = HashMap<K, V, S>;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

impl<K, V, S> DerefMut for AdditiveMap<K, V, S> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.0
    }
}

impl<K: Eq + Hash, V: AddAssign + Default, S: BuildHasher> AdditiveMap<K, V, S> {
    /// Modifies the existing value stored under `key`, or the default value for `V` if none, by
    /// adding `value_to_add`.
    pub fn insert_add(&mut self, key: K, value_to_add: V) {
        let current_value = self.0.entry(key).or_insert_with(Default::default);
        *current_value += value_to_add;
    }
}

impl<K: Eq + Hash, V, S: BuildHasher + Default> Default for AdditiveMap<K, V, S> {
    fn default() -> Self {
        Self(HashMap::with_hasher(Default::default()))
    }
}

impl<'a, K, V, S> IntoIterator for &'a AdditiveMap<K, V, S> {
    type Item = (&'a K, &'a V);
    type IntoIter = Iter<'a, K, V>;

    fn into_iter(self) -> Iter<'a, K, V> {
        self.0.iter()
    }
}

impl<'a, K, V, S> IntoIterator for &'a mut AdditiveMap<K, V, S> {
    type Item = (&'a K, &'a mut V);
    type IntoIter = IterMut<'a, K, V>;

    fn into_iter(self) -> IterMut<'a, K, V> {
        self.0.iter_mut()
    }
}

impl<K, V, S> IntoIterator for AdditiveMap<K, V, S> {
    type Item = (K, V);
    type IntoIter = IntoIter<K, V>;

    fn into_iter(self) -> IntoIter<K, V> {
        self.0.into_iter()
    }
}

impl<K: Eq + Hash, V, S: BuildHasher + Default> FromIterator<(K, V)> for AdditiveMap<K, V, S> {
    fn from_iter<T: IntoIterator<Item = (K, V)>>(iter: T) -> Self {
        Self(HashMap::from_iter(iter))
    }
}

impl<K: Eq + Hash, V: PartialEq, S: BuildHasher> PartialEq for AdditiveMap<K, V, S> {
    fn eq(&self, other: &AdditiveMap<K, V, S>) -> bool {
        self.0 == other.0
    }
}

impl<K: Eq + Hash, V: Eq, S: BuildHasher> Eq for AdditiveMap<K, V, S> {}

impl<K: Eq + Hash + Debug, V: Debug, S: BuildHasher> Debug for AdditiveMap<K, V, S> {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        self.0.fmt(f)
    }
}

#[cfg(test)]
mod tests {
    use super::AdditiveMap;
    use crate::transform::Transform;

    #[test]
    fn insert_add() {
        let key = "key";
        let mut int_map = AdditiveMap::new();
        int_map.insert_add(key, 1);
        assert_eq!(1, int_map[key]);
        int_map.insert_add(key, 2);
        assert_eq!(3, int_map[key]);

        let mut transform_map = AdditiveMap::new();
        transform_map.insert_add(key, Transform::AddUInt64(1));
        assert_eq!(Transform::AddUInt64(1), transform_map[key]);
        transform_map.insert_add(key, Transform::AddInt32(2));
        assert_eq!(Transform::AddInt32(3), transform_map[key]);
    }
}
