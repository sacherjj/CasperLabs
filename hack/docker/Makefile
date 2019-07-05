CL_CASPER_NUM_VALIDATORS ?= 3
CL_SERVER_HTTP_PORT ?= 40403

REFRESH_TARGETS := ./monitoring/prometheus/refresh-targets.sh

# Don't remove directories created as dependencies.
.SECONDARY:

# Remove all node-N environments.
clean: down $(shell find . -type d -name "node-*" | grep -v .casperlabs | grep -v keys | awk -F '/' '{print $$2"/down"}')
	docker network rm casperlabs || exit 0
	rm -rf .casperlabs
	rm -rf .make
	rm -rf keys

# Create a new node environment by linking to the template docker-complse.yml file.
node-%: .casperlabs
	$(eval N = $*)
	$(eval ENV = node-$(N)/.env)
	mkdir node-$(N)
	# Copy default env values. Create overrides with `export CL_<name>=<value>` before running `make node-X/up` commands.
	cp ${PWD}/template/.env $(ENV)

	@# Create an .env file to hold template variables for docker-compose.
	echo NODE_NUMBER=$(N) >> $(ENV)

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
	mkdir -p .casperlabs/bootstrap
	mkdir -p .casperlabs/genesis/system-account
	mkdir -p .casperlabs/genesis/blessed-contracts
	mkdir -p .casperlabs/node-0
	mkdir -p keys/account-0

	./get-blessed-contracts.sh .casperlabs/genesis/blessed-contracts/blessed-contracts.tar.gz

	../key-management/docker-gen-account-keys.sh .casperlabs/genesis/system-account
	../key-management/docker-gen-account-keys.sh keys/account-0
	../key-management/docker-gen-keys.sh .casperlabs/node-0

	cp -r .casperlabs/node-0/node-id .casperlabs/bootstrap/node-id
	(cat .casperlabs/node-0/validator-id; echo " $(CL_CASPER_NUM_VALIDATORS)") >> .casperlabs/genesis/bonds.txt

	bash -c 'i=1 ; while [[ $$i -lt $(CL_CASPER_NUM_VALIDATORS) ]] ; do \
		mkdir -p .casperlabs/node-$$i ; \
		mkdir -p keys/account-$$i ; \
		../key-management/docker-gen-keys.sh .casperlabs/node-$$i ; \
		../key-management/docker-gen-account-keys.sh keys/account-$$i ; \
		BOND=$$(( $(CL_CASPER_NUM_VALIDATORS)+2*$$i )) ; \
		(cat .casperlabs/node-$$i/validator-id; echo " $$BOND") >> .casperlabs/genesis/bonds.txt ; \
		((i = i + 1)) ; \
	done'

	@# Check that the files we wanted exist and aren't empty.
	[ -s .casperlabs/genesis/bonds.txt ]
	[ -s .casperlabs/bootstrap/node-id ]
