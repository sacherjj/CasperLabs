CL_CASPER_NUM_VALIDATORS ?= 10
CL_SERVER_HTTP_PORT ?= 40403
CL_VERSION ?= latest

REFRESH_TARGETS := ./monitoring/prometheus/refresh-targets.sh

# Don't remove directories created as dependencies.
.SECONDARY:

# Remove all node-N environments.
clean: down $(shell find . -type d -name "node-*" | awk -F '/' '{print $$2"/down"}')
	docker network rm casperlabs || exit 0
	rm -rf .casperlabs
	rm -rf .make


# Create a new node environment by linking to the template docker-complse.yml file.
node-%: .casperlabs
	$(eval N = $*)

	$(eval KEY_FILE_COUNT = $(shell find .casperlabs -type f -name *.sk | wc -l))
	[ "$(N)" -lt "$(KEY_FILE_COUNT)" ] || (echo "Can't find keyfile for node-$(N)" && exit 1)
	$(eval KEY_FILE = $(shell find .casperlabs -type f -name *.sk | sort | tail -n +$$((1+$(N))) | head -n 1))

	mkdir node-$(N)
	$(eval ENV = node-$(N)/.env)

	@# Create an .env file to hold template variables for docker-compose.
	echo NODE_NUMBER=$(N) > $(ENV)
	echo CL_VERSION=$(CL_VERSION) >> $(ENV)

	@# The name of the file is the public key. Content is the private key.
	echo CL_VALIDATOR_PUBLIC_KEY=$(shell echo "$(KEY_FILE)" | awk -F '[/.]' '{print $$4}') >> $(ENV)
	echo CL_VALIDATOR_PRIVATE_KEY=$(shell cat "$(KEY_FILE)") >> $(ENV)

	@# Link Dockerfile so we can change it and reapply.
	@#ln -s ${PWD}/template/docker-compose.yml node-$(N)/docker-compose.yml
	@#Alternatively just make a copy so you can edit it independently.
	cp ${PWD}/template/docker-compose.yml node-$(N)/docker-compose.yml


# Start node.
node-%/up: node-% .make/docker/network
	@# Go into the directory to pick up values from .env
	cd node-$* && docker-compose up -d
	$(REFRESH_TARGETS)

# Tear down node.
node-%/down:
	if [ -d node-$* ]; then \
		cd node-$* && docker-compose down && cd - && \
		rm -rf node-$* ; \
	fi
	$(REFRESH_TARGETS)

# Just a quick way to check whether the server published any Prometheus metrics yet.
node-%/metrics:
	docker run --rm --network casperlabs appropriate/curl -s http://node-$*:$(CL_SERVER_HTTP_PORT)/metrics


# Start common components.
up: .make/docker/network
	$(REFRESH_TARGETS)
	docker-compose -p casperlabs up -d

# Stop common components.
down:
	docker-compose -p casperlabs down


# Slow down traffic between nodes to simulate network effects and large blocks.
# If we slow it down too much then the client won't be able to connect though.
# https://alexei-led.github.io/post/pumba_docker_netem/
delay:
	docker run -it --rm -v /var/run/docker.sock:/var/run/docker.sock gaiaadm/pumba \
	    netem \
	      --duration 5m \
	      --interface eth0 \
	      --tc-image gaiadocker/iproute2 \
	      delay \
	        --time 500 \
	        --jitter 100 \
	        --distribution normal \
	      re2:^node

# Use the `rate` function to limit bandwidth.
slow:
	docker run -it --rm -v /var/run/docker.sock:/var/run/docker.sock gaiaadm/pumba \
	    netem \
	      --duration 5m \
	      --interface eth0 \
	      --tc-image gaiadocker/iproute2 \
	      rate \
	        --rate 100kbit \
	      re2:^node


.make/docker/network:
	docker network create casperlabs
	mkdir -p $(dir $@) && touch $@

# Generate keys and bonds.
.casperlabs:
	@# Start a node to generate keys.
	mkdir -p .bootstrap
	docker run --rm -d \
		--name make-genesis \
		-v ${PWD}/.bootstrap:/root/.casperlabs \
		casperlabs/node:$(CL_VERSION) \
		run -s \
		--casper-num-validators $(CL_CASPER_NUM_VALIDATORS)

	@# Wait until the node is running, so we know it generated the files. Capture the Node ID fromt the logs.
	@# Can't write to the bootstrap dir from here as it's owned by the docker user.
	NODE_ID=`(docker logs -f make-genesis &) | grep -m 1 -Po 'Listening for traffic on casperlabs://\K[^@]+'` && \
	docker run --rm \
		-v ${PWD}/.bootstrap:/root/.casperlabs \
		--entrypoint sh \
		casperlabs/node:$(CL_VERSION) \
		-c "echo $$NODE_ID > /root/.casperlabs/node-id"
	docker stop make-genesis

	@# Copy files to where we can mount them separately from.
	mkdir -p .casperlabs/bootstrap
	cp -r .bootstrap/genesis .casperlabs
	cp .bootstrap/node* .casperlabs/bootstrap

	@# Removing genesis created by docker, using docker, due to permissions.
	docker run --rm \
		-v ${PWD}/.bootstrap:/root/.casperlabs \
		--entrypoint sh \
		casperlabs/node:$(CL_VERSION) \
		-c "rm -rf /root/.casperlabs/*"
	rm -rf .bootstrap

	@# Check that the files we wanted exist and aren't empty.
	[ -s .casperlabs/genesis/bonds.txt ]
	[ -s .casperlabs/bootstrap/node-id ]