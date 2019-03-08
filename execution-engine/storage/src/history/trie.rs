//! Core types for a Merkle Trie

use shared::newtypes::Blake2bHash;

const RADIX: usize = 256;

/// Represents a pointer to the next object in a Merkle Trie
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum Pointer {
    LeafPointer(Blake2bHash),
    NodePointer(Blake2bHash),
}

/// Represents the underlying structure of a node in a Merkle Trie
#[derive(Copy, Clone)]
pub struct PointerBlock([Option<Pointer>; RADIX]);

impl PartialEq for PointerBlock {
    #[inline]
    fn eq(&self, other: &PointerBlock) -> bool {
        self.0[..] == other.0[..]
    }
}

impl Eq for PointerBlock {}

impl Default for PointerBlock {
    fn default() -> Self {
        PointerBlock([Default::default(); RADIX])
    }
}

impl PointerBlock {
    pub fn new() -> Self {
        Default::default()
    }
}

impl ::std::ops::Index<usize> for PointerBlock {
    type Output = Option<Pointer>;

    #[inline]
    fn index(&self, index: usize) -> &Self::Output {
        let PointerBlock(dat) = self;
        &dat[index]
    }
}

impl ::std::ops::IndexMut<usize> for PointerBlock {
    #[inline]
    fn index_mut(&mut self, index: usize) -> &mut Self::Output {
        let PointerBlock(dat) = self;
        &mut dat[index]
    }
}

/// Represents a Merkle Trie
#[derive(Clone, PartialEq, Eq)]
pub enum Trie<K, V> {
    Leaf { key: K, value: V },
    Node { pointer_block: Box<PointerBlock> },
    Extension { affix: Vec<u8>, pointer: Pointer },
}

#[cfg(test)]
mod tests {
    mod pointer_block {
        use crate::history::trie::*;

        #[test]
        fn assignment_and_indexing() {
            let test_hash = Blake2bHash::new(b"TrieTrieAgain");
            let leaf_pointer = Some(Pointer::LeafPointer(test_hash));
            let mut pointer_block = PointerBlock::new();
            pointer_block[0] = leaf_pointer;
            pointer_block[RADIX - 1] = leaf_pointer;
            assert_eq!(leaf_pointer, pointer_block[0]);
            assert_eq!(leaf_pointer, pointer_block[RADIX - 1]);
            assert_eq!(None, pointer_block[1]);
            assert_eq!(None, pointer_block[RADIX - 2]);
        }

        #[test]
        #[should_panic]
        fn assignment_off_end() {
            let test_hash = Blake2bHash::new(b"TrieTrieAgain");
            let leaf_pointer = Some(Pointer::LeafPointer(test_hash));
            let mut pointer_block = PointerBlock::new();
            pointer_block[RADIX] = leaf_pointer;
        }

        #[test]
        #[should_panic]
        fn indexing_off_end() {
            let pointer_block = PointerBlock::new();
            let _val = pointer_block[RADIX];
        }
    }
}
