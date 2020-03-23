FROM openjdk:11-jre-slim

RUN apt-get clean && rm -rf /var/lib/apt/lists/* && apt-get update && apt-get install -yq openssl
LABEL MAINTAINER="CasperLabs, LLC. <info@casperlabs.io>"

USER root
WORKDIR /opt/docker
# HOME points to /root
RUN mkdir -p /root/.casperlabs/genesis
RUN mkdir -p /root/.casperlabs/deploy
ENTRYPOINT ["casperlabs-node"]
CMD ["run"]

ADD ./target/casperlabs-node_*.deb /opt/docker/

RUN dpkg -i \
	--ignore-depends openjdk-11-jre-headless \
	/opt/docker/casperlabs-node_*.deb
