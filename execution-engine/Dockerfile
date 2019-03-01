# Based on the image which openjdk:11-jre-slim is built on.
FROM debian:stretch-slim

LABEL MAINTAINER="CasperLabs, LLC. <info@casperlabs.io>"

USER root
WORKDIR /opt/docker

# Will need to mount a common volume to be used as a socket.
ENTRYPOINT ["casperlabs-engine-grpc-server"]
CMD [".casper-node.sock"]

# Install from debian package.
ADD ./casperlabs-engine-grpc-server*.deb /opt/docker/
RUN dpkg -i /opt/docker/casperlabs-engine-grpc-server*.deb