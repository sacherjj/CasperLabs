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

pub struct Accumulator<T> {
    main: Arc<Mutex<VecDeque<T>>>,
    alt: Arc<Mutex<VecDeque<T>>>,
    timer: Arc<RwLock<Instant>>,
    poll_length: Arc<Duration>,
}

impl<T: Clone> Accumulator<T> {
    pub fn new(poll_length: Duration) -> Self {
        let main = Arc::new(Mutex::new(VecDeque::new()));
        let alt = Arc::new(Mutex::new(VecDeque::new()));
        let timer = Arc::new(RwLock::new(Instant::now()));
        let poll_length = Arc::new(poll_length);
        Accumulator {
            main,
            alt,
            timer,
            poll_length,
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
            if {
                let timer = self.timer.read()?;
                timer.elapsed() > *self.poll_length
            } {
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
        let poll_length = Arc::clone(&self.poll_length);
        Accumulator {
            main,
            alt,
            timer,
            poll_length,
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
