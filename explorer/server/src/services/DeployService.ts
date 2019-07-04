import { grpc } from "@improbable-eng/grpc-web";
import { NodeHttpTransport } from "@improbable-eng/grpc-web-node-http-transport";
import { ProtobufMessage } from "@improbable-eng/grpc-web/dist/typings/message";
import { Deploy } from "../../../grpc/generated/io/casperlabs/casper/consensus/consensus_pb";
import { DeployRequest } from "../../../grpc/generated/io/casperlabs/node/api/casper_pb";
import { CasperService } from "../../../grpc/generated/io/casperlabs/node/api/casper_pb_service";

// https://github.com/improbable-eng/grpc-web/tree/master/client/grpc-web
// https://www.npmjs.com/package/@improbable-eng/grpc-web-node-http-transport

export default class DeployService {
  constructor(
    private nodeUrl: string,
  ) { }

  public deploy(deploy: Deploy) {
    return new Promise<void>((resolve, reject) => {
      const deployRequest = new DeployRequest();
      deployRequest.setDeploy(deploy);

      grpc.unary(CasperService.Deploy, {
        host: this.nodeUrl,
        request: deployRequest,
        transport: NodeHttpTransport(),
        onEnd: (res) => {
          if (res.status === grpc.Code.OK) {
            resolve();
          } else {
            reject(this.error(res));
          }
        }
      });
    });
  }

  private error<T extends ProtobufMessage>(res: grpc.UnaryOutput<T>) {
    console.log("gRPC error: ", res.status, res.statusMessage);
    return new Error(`gRPC error: code=${res.status}, message=${res.statusMessage}`);
  }
}
