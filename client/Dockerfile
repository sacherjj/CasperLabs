FROM openjdk:11-jre-slim

RUN apt-get update && apt-get install -yq openssl 
LABEL MAINTAINER="CasperLabs, LLC. <info@casperlabs.io>"

USER root 
WORKDIR /opt/docker 
ENTRYPOINT ["casperlabs-client"]
CMD ["run"]

ADD ./target/casperlabs-client_*.deb /opt/docker/

RUN dpkg -i \
	--ignore-depends openjdk-11-jre-headless \
	/opt/docker/casperlabs-client_*.deb
