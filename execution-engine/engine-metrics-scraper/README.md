# casperlabs-engine-metrics-scraper

Usage:
```
casperlabs-engine-grpc-server test-socket | tee >(casperlabs-engine-metrics-scraper --addr 127.0.0.1:40403 --expiration-duration-millis 12000)
```