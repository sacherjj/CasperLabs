use crate::queue::Queue;

pub trait QueueProvider {
    /// Reads bonding queue.
    fn read_bonding() -> Queue;

    /// Reads unbonding queue.
    fn read_unbonding() -> Queue;

    /// Writes bonding queue.
    fn write_bonding(queue: Queue);

    /// Writes unbonding queue.
    fn write_unbonding(queue: Queue);
}
