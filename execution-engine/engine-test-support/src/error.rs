/// The error type returned by any casperlabs-engine-test-support operation.
#[derive(Eq, PartialEq, Ord, PartialOrd, Clone, Hash, Debug)]
pub struct Error {
    inner: String,
}
