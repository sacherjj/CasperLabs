use std::collections::vec_deque::VecDeque;
use std::error::Error;
use std::fmt;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError, RwLock};
use std::time::{Duration, Instant};

#[derive(Debug, Copy, Clone)]
pub enum AccumulationError {
    PoisonError,
}

impl<T> From<PoisonError<T>> for AccumulationError {
    fn from(error: PoisonError<T>) -> Self {
        AccumulationError::PoisonError
    }
}

impl fmt::Display for AccumulationError {
    fn fmt(&self, f: &mut fmt::Formatter) -> Result<(), fmt::Error> {
        match self {
            AccumulationError::PoisonError => write!(f, "thread was poisoned"),
        }
    }
}

pub struct Accumulator<T> {
    main: Arc<Mutex<VecDeque<T>>>,
    alt: Arc<Mutex<VecDeque<T>>>,
    timer: Arc<RwLock<Instant>>,
    poll_length: Arc<Duration>,
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

pub trait Pusher<T> {
    fn push(&self, t: T) -> Result<(), AccumulationError>;
}

impl<T: Clone> Pusher<T> for Accumulator<T> {
    fn push(&self, t: T) -> Result<(), AccumulationError> {
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

pub trait Drainer<T>: Clone + Send + Sync {
    fn drain(&self) -> Result<Vec<T>, AccumulationError>;
}

impl<T: Clone + Send + Sync> Drainer<T> for Accumulator<T> {
    fn drain(&self) -> Result<Vec<T>, AccumulationError> {
        let mut main_guard = self.main.lock()?;
        let mut timer_guard = self.timer.write()?;
        let ret = main_guard.drain(..).collect();
        *timer_guard = Instant::now();
        Ok(ret)
    }
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

    pub fn is_empty(&self) -> Result<bool, AccumulationError> {
        if let Ok(mut main_guard) = self.main.try_lock() {
            let mut alt_guard = self.alt.lock()?;
            Ok(main_guard.is_empty() && alt_guard.is_empty())
        } else {
            let mut alt_guard = self.alt.lock()?;
            Ok(alt_guard.is_empty())
        }
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;

    #[test]
    fn should_accept_string_value() {
        let expected_poll_length = Duration::new(5, 0);

        let state = Accumulator::new(expected_poll_length);

        state.push("foo").expect("should push");

        assert!(
            !state.is_empty().expect("should is_empty"),
            "should not be empty"
        );
    }

    #[test]
    fn should_return_string_value() {
        let expected_poll_length = Duration::new(5, 0);

        let state = Accumulator::new(expected_poll_length);

        state.push("foo");

        let mut items = state.drain().expect("should drain");

        let item = items.pop().expect("should have item");

        assert_eq!(item, "foo", "should have pushed value");
    }
}
