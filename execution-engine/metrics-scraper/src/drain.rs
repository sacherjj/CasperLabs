use std::net::SocketAddr;
use std::thread;
use std::thread::JoinHandle;

use hyper::rt::Future;
use hyper::{service, Body, Method, Request, Response, Server, StatusCode};

use crate::accumulator::Drainer;

fn handler_factory<D: Drainer<String>>(
    drainer: D,
    endpoint: &'static str,
) -> impl FnMut(Request<Body>) -> Response<Body> {
    move |req: Request<Body>| {
        let mut response = Response::new(Body::empty());
        match (req.method(), req.uri().path()) {
            (&Method::GET, path) if path == endpoint => match drainer.drain() {
                Ok(ret) => {
                    *response.body_mut() = Body::from(ret.join("\n"));
                }
                Err(err) => {
                    *response.status_mut() = StatusCode::INTERNAL_SERVER_ERROR;
                    *response.body_mut() = Body::from(format!("{:?}", err));
                }
            },
            _ => {
                *response.status_mut() = StatusCode::NOT_FOUND;
            }
        };
        response
    }
}

/// Spawns a thread which runs a web server that serves the contents of the given accumulator in the
/// body of responses to incoming requests at the given socket and endpoint.  Each request at this
/// endpoint (destructively) drains the accumulator.
pub fn open_drain<D: Drainer<String> + 'static>(
    drainer: D,
    addr: &SocketAddr,
    endpoint: &'static str,
) -> JoinHandle<()> {
    let service = move || {
        let drainer = drainer.clone();
        service::service_fn_ok(handler_factory(drainer, endpoint))
    };
    let server = Server::bind(addr)
        .serve(service)
        .map_err(|e| eprintln!("server error: {}", e));
    thread::spawn(move || hyper::rt::run(server))
}

#[cfg(test)]
mod tests {
    use std::string::ToString;
    use std::time::Duration;

    use futures::stream::Stream;
    use hyper::Client;
    use tokio::runtime::current_thread;

    use super::*;
    use crate::accumulator::{Accumulator, Pusher};

    fn fetch_url(url: hyper::Uri) -> impl Future<Item = String, Error = hyper::error::Error> {
        let client = Client::new();

        client.get(url).and_then(|res| {
            res.into_body().concat2().and_then(|body| {
                // TODO: don't unwrap
                let body = std::str::from_utf8(&body).unwrap();
                futures::future::ok(body.to_string())
            })
        })
    }

    #[test]
    fn test_drain_endpoint() {
        let drain = Accumulator::new(Duration::new(5, 0));
        let addr: SocketAddr = ([127, 0, 0, 1], 3000).into();
        let endpoint: hyper::Uri = format!("http://{}/metrics", addr)
            .parse()
            .expect("should parse");

        let expected = r#"2019-06-05T22:24:35.878Z METRIC 6 system76-pc casperlabs-engine-grpc-server payload={"timestamp":"2019-06-05T22:24:35.878Z","process_id":6507,"process_name":"casperlabs-engine-grpc-server","host_name":"system76-pc","log_level":"Metric","priority":6,"message_type":"ee-structured","message_type_version":"1.0.0","message_id":"6682069017946818164","description":"trie_store_write_duration write 0.001382911","properties":{"correlation_id":"38b81cd8-b089-42c0-bdeb-2e3dc2a91255","duration_in_seconds":"0.001382911","message":"trie_store_write_duration write 0.001382911","message_template":"{message}","time-series-data":"trie_store_write_duration{tag=\"write\", correlation_id=\"38b81cd8-b089-42c0-bdeb-2e3dc2a91255\"} 0.001382911 1559773475878"}}"#.to_string();

        open_drain(Accumulator::clone(&drain), &addr, "/metrics");

        drain.push(expected.to_owned()).expect("should push");

        let actual = {
            let mut runtime = current_thread::Runtime::new().expect("should create runtime");
            runtime
                .block_on(fetch_url(endpoint.to_owned()))
                .expect("should block on future")
        };

        assert_eq!(expected, actual);

        let empty = {
            let mut runtime = current_thread::Runtime::new().expect("should create runtime");
            runtime
                .block_on(fetch_url(endpoint.to_owned()))
                .expect("should block on future")
        };

        assert_eq!("", empty);
    }
}
