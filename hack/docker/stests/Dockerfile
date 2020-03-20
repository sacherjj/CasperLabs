FROM python:3.7-slim

RUN apt update && apt install -y git curl pipenv

# Use bash so up/down arrow work in the interactive console.
ENV SHELL /bin/bash
WORKDIR /root/casperlabs

# Add some randomness so cache is busted every time we build.
# ADD "https://www.random.org/cgi-bin/randbyte?nbytes=10&format=h" skipcache
ARG CACHEBUST=0

RUN curl https://raw.githubusercontent.com/CasperLabs/stests/master/installer | bash

# Make the variables configurable via env vars by turning
# `export STESTS_BROKER_REDIS_HOST=localhost` into
# `export STESTS_BROKER_REDIS_HOST=${STESTS_BROKER_REDIS_HOST:-localhost}`
RUN sed -i -E 's/([A-Z_]+)=(.+)/\1=${\1:-\2}/' ~/.casperlabs-stests/vars

# Replace the old client with a new one we prepared with some extra wasms in it.
COPY .build/casperlabs_client-*.tar.gz stests/
RUN cd stests && \
  pipenv uninstall casperlabs-client && \
  pipenv install casperlabs_client-*.tar.gz
