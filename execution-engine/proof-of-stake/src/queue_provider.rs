use crate::queue::Queue;

pub trait QueueProvider {
    /// Reads bonding queue.
    fn read_bonding(&mut self) -> Queue;

    /// Reads unbonding queue.
    fn read_unbonding(&mut self) -> Queue;

    /// Writes bonding queue.
    fn write_bonding(&mut self, queue: Queue);

    /// Writes unbonding queue.
    fn write_unbonding(&mut self, queue: Queue);
}
