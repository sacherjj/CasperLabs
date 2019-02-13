FROM io.casperlabs/node:latest

# Using iproute2 for network simulation with `tc`
RUN apt-get update && apt-get install -yq iproute2
