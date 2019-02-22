# Based on the image which openjdk:11-jre-slim is built on.
FROM debian:stretch-slim

LABEL MAINTAINER="CasperLabs, LLC. <info@casperlabs.io>"

USER root
WORKDIR /opt/docker
# Will need to mount a common volume to be used as a socket.
ENTRYPOINT ["./casperlabs-engine-grpc-server"]
CMD [".casper-node.sock"]

COPY casperlabs-engine-grpc-server /opt/docker/casperlabs-engine-grpc-server
RUN chmod +x /opt/docker/casperlabs-engine-grpc-server