use std::io;
use std::io::BufRead;

use crate::accumulator::Pusher;

pub fn start_sink<P: Pusher<String>>(pusher: P) {
    let stdin = io::stdin();
    let handle = stdin.lock();

    for line in handle.lines() {
        // Okay to panic here
        let line = line.unwrap();
        pusher.push(line).unwrap();
    }
}
