use std::io::{self, BufRead};
use std::thread;
use std::time::Duration;

use hyper::rt::Future;
use hyper::{service, Body, Method, Request, Response, Server, StatusCode};

use metrics_scraper::accumulator::Accumulator;

fn handler_factory(state: Accumulator<String>) -> impl FnMut(Request<Body>) -> Response<Body> {
    move |req: Request<Body>| {
        let mut response = Response::new(Body::empty());

        match (req.method(), req.uri().path()) {
            (&Method::GET, "/") => match state.drain() {
                Ok(ret) => {
                    *response.body_mut() = Body::from(ret.join("\n"));
                }
                Err(err) => {
                    *response.status_mut() = StatusCode::INTERNAL_SERVER_ERROR;
                    *response.body_mut() = Body::from(err.to_string());
                }
            },
            _ => {
                *response.status_mut() = StatusCode::NOT_FOUND;
            }
        };

        response
    }
}

fn main() -> io::Result<()> {
    // TODO: args
    let expected_poll_length = Duration::new(5, 0);
    let addr = ([127, 0, 0, 1], 3000).into();

    let state = Accumulator::new(expected_poll_length);

    {
        let state = state.clone();
        let service = move || {
            let state = state.clone();
            service::service_fn_ok(handler_factory(state))
        };
        let server = Server::bind(&addr)
            .serve(service)
            .map_err(|e| eprintln!("server error: {}", e));

        thread::spawn(move || hyper::rt::run(server));
    }

    {
        let state = state.clone();
        let stdin = io::stdin();
        let handle = stdin.lock();

        // panicking is acceptable here
        for line in handle.lines() {
            let line = line.unwrap();
            state.push(line).unwrap();
        }
    }

    Ok(())
}
