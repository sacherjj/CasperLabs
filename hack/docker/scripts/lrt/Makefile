$(eval RUNPY = PYTHONPATH=$${PYTHONPATH}:. pipenv run python)

all: deploy run


deploy:
	${RUNPY} dramatiq_erc20.py deploy

run:
	${RUNPY} dramatiq_erc20.py run

restart-nodes:
	./restart_nodes.sh

poll:
	${RUNPY} poll_new_deploys.py
