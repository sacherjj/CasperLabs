# casperlabs-engine-metrics-scraper

Usage:
```
casperlabs-engine-grpc-server test-socket --log-metrics | tee >(casperlabs-engine-metrics-scraper --addr 127.0.0.1:40403 --expiration-duration-millis 12000)
```

To pull data from the scraper:
```
while sleep 5; do curl 127.0.0.1:40403/metrics; done
```
