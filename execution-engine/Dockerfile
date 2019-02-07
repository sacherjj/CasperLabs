# Based on the image which openjdk:11-jre-slim is built on.
FROM debian:stretch-slim

LABEL MAINTAINER="CasperLabs, LLC. <info@casperlabs.io>"

USER root
WORKDIR /opt/docker
# Will need to mount a common volume to be used as a socket.
ENTRYPOINT ["./engine-grpc-server"]
CMD [".casper-node.sock"]

COPY engine-grpc-server /opt/docker/engine-grpc-server
RUN chmod +x /opt/docker/engine-grpc-server