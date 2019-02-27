FROM casperlabs/node:latest

# Using iproute2 for network simulation with `tc`.
# iptables can also be used to block individual ports.
RUN apt-get update && apt-get install -yq iproute2 iptables
