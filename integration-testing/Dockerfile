FROM python:3.6.8-slim

LABEL MAINTAINER="CasperLabs, LLC. <info@casperlabs.io>"

USER root
RUN python3 -m pip install pipenv docker pytest pytest-json pytest-mypy pytest-pylint typing-extensions dataclasses grpcio protobuf

EXPOSE 40500-40700

WORKDIR /root/integration-testing
ENTRYPOINT ["/root/integration-testing/run_tests.sh"]
RUN mkdir -p /root/integration-testing
COPY ./ /root/integration-testing/
