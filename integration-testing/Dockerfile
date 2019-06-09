FROM python:3.6.8-slim

LABEL MAINTAINER="CasperLabs, LLC. <info@casperlabs.io>"

RUN apt-get update && apt-get -y install gcc
RUN python3 -m pip install pipenv docker pytest pytest-json pytest-mypy pytest-pylint typing-extensions dataclasses grpcio grpcio_tools protobuf in-place ed25519 pyblake2

ENTRYPOINT ["/root/integration-testing/run_tests.sh"]

WORKDIR /root/integration-testing

# We still need to copy this rather than volume,
# because it is "behind" the location where we docker-compose.
RUN mkdir -p /root/protobuf
COPY ./protobuf/ /root/protobuf/
