#!/usr/bin/env python3

from casperlabs_client import grpc_proxy

# --host localhost --port 40401 --certificate-file /home/piotr/CasperLabs/integration-testing/resources/bootstrap_certificate/node-0.certificate.pem --node-id 4d802045c3e4d2e031f25878517bc8e2c9710ee7 show-blocks --depth 1
grpc_proxy.proxy_client(
    certificate_file="/home/piotr/CasperLabs/integration-testing/resources/bootstrap_certificate/node-0.certificate.pem",
    node_id="4d802045c3e4d2e031f25878517bc8e2c9710ee7",
)
