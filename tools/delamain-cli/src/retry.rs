//! Small, network-free retry-with-exponential-backoff helper.
//!
//! Kept separate from the HTTP client so it can be unit tested without a
//! server: the sleep function and the operation are both injected.

use std::time::Duration;

/// Run `op` up to `max_attempts` times. Sleeps via `sleep_fn` between
/// attempts (not after the last one), doubling the delay starting at
/// `base_delay` each time. Returns the last error if all attempts fail.
pub fn retry_with_backoff<T, E>(
    max_attempts: u32,
    base_delay: Duration,
    mut op: impl FnMut(u32) -> Result<T, E>,
    mut sleep_fn: impl FnMut(Duration),
) -> Result<T, E> {
    assert!(max_attempts >= 1, "max_attempts must be >= 1");
    let mut attempt = 1;
    loop {
        match op(attempt) {
            Ok(v) => return Ok(v),
            Err(e) => {
                if attempt >= max_attempts {
                    return Err(e);
                }
                let delay = base_delay * 2u32.pow(attempt - 1);
                sleep_fn(delay);
                attempt += 1;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    #[test]
    fn succeeds_on_first_try_without_sleeping() {
        let sleeps: RefCell<Vec<Duration>> = RefCell::new(Vec::new());
        let result: Result<i32, &str> = retry_with_backoff(
            3,
            Duration::from_millis(10),
            |_attempt| Ok(42),
            |d| sleeps.borrow_mut().push(d),
        );
        assert_eq!(result, Ok(42));
        assert!(sleeps.borrow().is_empty());
    }

    #[test]
    fn retries_then_succeeds_uses_exponential_delays() {
        let sleeps: RefCell<Vec<Duration>> = RefCell::new(Vec::new());
        let mut calls = 0;
        let result: Result<i32, &str> = retry_with_backoff(
            5,
            Duration::from_millis(10),
            |_attempt| {
                calls += 1;
                if calls < 3 {
                    Err("transient")
                } else {
                    Ok(7)
                }
            },
            |d| sleeps.borrow_mut().push(d),
        );
        assert_eq!(result, Ok(7));
        assert_eq!(calls, 3);
        assert_eq!(
            sleeps.into_inner(),
            vec![Duration::from_millis(10), Duration::from_millis(20)]
        );
    }

    #[test]
    fn exhausts_all_attempts_and_returns_last_error() {
        let mut calls = 0;
        let result: Result<i32, &str> = retry_with_backoff(
            3,
            Duration::from_millis(1),
            |_attempt| {
                calls += 1;
                Err("still failing")
            },
            |_| {},
        );
        assert_eq!(result, Err("still failing"));
        assert_eq!(calls, 3);
    }

    #[test]
    fn single_attempt_never_sleeps() {
        let sleeps: RefCell<Vec<Duration>> = RefCell::new(Vec::new());
        let mut calls = 0;
        let result: Result<i32, &str> = retry_with_backoff(
            1,
            Duration::from_millis(50),
            |_attempt| {
                calls += 1;
                Err("nope")
            },
            |d| sleeps.borrow_mut().push(d),
        );
        assert_eq!(result, Err("nope"));
        assert_eq!(calls, 1);
        assert!(sleeps.borrow().is_empty());
    }
}
