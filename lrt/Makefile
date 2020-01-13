all:
	PYTHONPATH=${PYTHONPATH}:. pipenv run python dramatiq_erc20.py deploy
	PYTHONPATH=${PYTHONPATH}:. pipenv run python dramatiq_erc20.py run

restart-nodes:
	./restart_nodes.sh

poll:
	PYTHONPATH=${PYTHONPATH}:. pipenv run python poll_new_deploys.py
