#!/usr/bin/env sh

# Run a piece of script in the buildenv image but make sure the artifacts produced
# are owned by the host user rather than root.

CMD=$1
USERID=$(id -u)

docker pull casperlabs/buildenv:latest
docker run --rm \
  -v ${PWD}:/CasperLabs \
  --entrypoint sh \
  casperlabs/buildenv:latest \
  -c "\
		apt-get install sudo ; \
		useradd -u ${USERID} -m builder ; \
		cp -r /root/. /home/builder/ ; \
		chown -R builder /home/builder ; \
		sudo -u builder bash -c '\
			export HOME=/home/builder ; \
			export PATH=/home/builder/.cargo/bin:\$PATH ; \
			cd /CasperLabs ; \
			${CMD} \
	'"
