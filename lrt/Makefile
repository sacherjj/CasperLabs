all:
	cd ~/CasperLabs/integration-testing/client/CasperLabsClient/ && ./install.sh
	PYTHONPATH=${PYTHONPATH}:. pipenv run python dramatiq_erc20.py 

restart-nodes:
	./restart_nodes.sh
