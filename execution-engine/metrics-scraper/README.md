# metrics-scraper

Usage:
```
casperlabs-engine-grpc-server test-socket | tee >(metrics-scraper --addr 127.0.0.1:40403 --expiration-duration-millis 12000)
```