
# Create a new node project by linking to the template docker-complse.yml file.
node-%:
	$(eval N = $*)
	mkdir node-$(N)
	ln -s ${PWD}/template/docker-compose.yml node-$(N)/docker-compose.yml

up/node-%: node-%
	docker-compose -f node-$*/docker-compose.yml up -d

down/node-%:
	$(eval N = $*)
	docker-compose -f node-$(N)/docker-compose.yml down
	rm -rf node-$(N)