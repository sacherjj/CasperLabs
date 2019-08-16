use std::collections::vec_deque::VecDeque;
use std::fmt;
use std::sync::{Arc, Mutex, PoisonError, RwLock};
use std::time::{Duration, Instant};

pub trait Pusher<T> {
    type Error: fmt::Debug;

    fn push(&self, t: T) -> Result<(), Self::Error>;
}

pub trait Drainer<T>: Clone + Send + Sync {
    type Error: fmt::Debug;

    fn drain(&self) -> Result<Vec<T>, Self::Error>;
}

#[derive(Debug, Copy, Clone)]
pub enum AccumulationError {
    PoisonError,
}

impl<T> From<PoisonError<T>> for AccumulationError {
    fn from(_error: PoisonError<T>) -> Self {
        AccumulationError::PoisonError
    }
}

/// A purpose-built, time-bounded queue where we have [`drain`](Accumulator::drain) instead of `pop`.
/// By "time-bounded", we mean that if the queue isn't drained within a given duration since
/// creation or the last call to [`drain`](Accumulator::drain), then each subsequent
/// [`push`](Accumulator::push) will remove the oldest item in the queue.

/// It can be shared across threads. Because it is purpose-built for this application, it is
/// expected that there will be a single pusher and a single drainer, though it can support multiple
/// drainers. It is designed to ensure that a call to push is not blocked by a call to drain. The
/// behavior with multiple pushers is unspecified, as it is currently not designed to be used with
/// multiple pushers.
pub struct Accumulator<T> {
    main: Arc<Mutex<VecDeque<T>>>,
    alt: Arc<Mutex<VecDeque<T>>>,
    timer: Arc<RwLock<Instant>>,
    expiration_duration: Arc<Duration>,
}

impl<T: Clone> Accumulator<T> {
    pub fn new(expiration_duration: Duration) -> Self {
        let main = Arc::new(Mutex::new(VecDeque::new()));
        let alt = Arc::new(Mutex::new(VecDeque::new()));
        let timer = Arc::new(RwLock::new(Instant::now()));
        let expiration_duration = Arc::new(expiration_duration);
        Accumulator {
            main,
            alt,
            timer,
            expiration_duration,
        }
    }
}

impl<T: Clone> Pusher<T> for Accumulator<T> {
    type Error = AccumulationError;

    fn push(&self, t: T) -> Result<(), Self::Error> {
        if let Ok(mut main_guard) = self.main.try_lock() {
            let mut alt_guard = self.alt.lock()?;
            if !alt_guard.is_empty() {
                main_guard.append(&mut alt_guard);
            }
            let expired = {
                let timer = self.timer.read()?;
                timer.elapsed() > *self.expiration_duration
            };
            if expired {
                main_guard.pop_front();
            }
            main_guard.push_back(t);
            Ok(())
        } else {
            let mut alt_guard = self.alt.lock()?;
            alt_guard.push_back(t);
            Ok(())
        }
    }
}

impl<T: Clone + Send + Sync> Drainer<T> for Accumulator<T> {
    type Error = AccumulationError;

    fn drain(&self) -> Result<Vec<T>, Self::Error> {
        let mut main_guard = self.main.lock()?;
        let mut timer_guard = self.timer.write()?;
        let ret = main_guard.drain(..).collect();
        *timer_guard = Instant::now();
        Ok(ret)
    }
}

impl<T> Clone for Accumulator<T> {
    fn clone(&self) -> Self {
        let main = Arc::clone(&self.main);
        let alt = Arc::clone(&self.alt);
        let timer = Arc::clone(&self.timer);
        let poll_length = Arc::clone(&self.expiration_duration);
        Accumulator {
            main,
            alt,
            timer,
            expiration_duration: poll_length,
        }
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;

    #[test]
    fn should_drain_when_empty() {
        let expected: Vec<String> = vec![];

        let actual = {
            let accumulator: Accumulator<String> = Accumulator::new(Duration::new(5, 0));
            accumulator.drain().expect("should drain")
        };

        assert_eq!(expected, actual);
    }

    #[test]
    fn should_drain_single_value() {
        let expected = vec!["foo"];

        let actual = {
            let accumulator = Accumulator::new(Duration::new(5, 0));
            for item in &expected {
                accumulator.push(*item).expect("should push");
            }
            accumulator.drain().expect("should drain")
        };

        assert_eq!(expected, actual);
    }

    #[test]
    fn should_drain_multiple_values() {
        let expected = vec!["foo", "bar"];

        let actual = {
            let accumulator = Accumulator::new(Duration::new(5, 0));
            for item in &expected {
                accumulator.push(*item).expect("should push");
            }
            accumulator.drain().expect("should drain")
        };

        assert_eq!(expected, actual);
    }

    #[test]
    fn should_fully_drain() {
        let accumulator = Accumulator::new(Duration::new(5, 0));

        let expected = vec!["foo", "bar"];

        let actual = {
            for item in &expected {
                accumulator.push(*item).expect("should push");
            }
            accumulator.drain().expect("should drain")
        };

        assert_eq!(expected, actual);

        let empty = accumulator.drain().expect("should drain");

        assert!(empty.is_empty());
    }
}
