use std::io;
use std::io::BufRead;

use crate::accumulator::Pusher;

pub fn start_sink<P: Pusher<String>>(pusher: P) {
    let stdin = io::stdin();
    let handle = stdin.lock();
    let mut lines = handle.lines();

    while let Some(line) = lines.next() {
        // Okay to panic here
        let line = line.unwrap();
        pusher.push(line).unwrap();
    }
}
