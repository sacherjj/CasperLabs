import { grpc } from '@improbable-eng/grpc-web';
import { CasperService as GrpcCasperService } from '../grpc/io/casperlabs/node/api/casper_pb_service';
import { DeployInfo } from '../grpc/io/casperlabs/casper/consensus/info_pb';
import { GetDeployInfoRequest } from '../grpc/io/casperlabs/node/api/casper_pb';
import { encodeBase16 } from '../lib/Conversions';
import { ProtobufMessage } from '@improbable-eng/grpc-web/dist/typings/message';
import { GrpcError } from './Errors';

export default class CasperService {
  constructor(
    // Point at either at a URL on a different port where grpcwebproxy is listening,
    // or use nginx to serve the UI files, the API and gRPC all on the same port without CORS.
    private url: string
  ) {}

  private error<T extends ProtobufMessage>(res: grpc.UnaryOutput<T>) {
    return new GrpcError(res.status, res.statusMessage);
  }

  getDeployInfo(deployHash: ByteArray): Promise<DeployInfo> {
    return new Promise<DeployInfo>((resolve, reject) => {
      const request = new GetDeployInfoRequest();
      request.setDeployHashBase16(encodeBase16(deployHash));

      grpc.unary(GrpcCasperService.GetDeployInfo, {
        host: this.url,
        request: request,
        onEnd: res => {
          if (res.status === grpc.Code.OK) {
            resolve(res.message as DeployInfo);
          } else {
            reject(this.error(res));
          }
        }
      });
    });
  }
}
