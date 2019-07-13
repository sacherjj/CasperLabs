import { grpc } from '@improbable-eng/grpc-web';
import { CasperService as GrpcCasperService } from '../grpc/io/casperlabs/node/api/casper_pb_service';
import {
  DeployInfo,
  BlockInfo
} from '../grpc/io/casperlabs/casper/consensus/info_pb';
import {
  GetDeployInfoRequest,
  StreamBlockInfosRequest
} from '../grpc/io/casperlabs/node/api/casper_pb';
import { encodeBase16 } from '../lib/Conversions';
import { GrpcError } from './Errors';

export default class CasperService {
  constructor(
    // Point at either at a URL on a different port where grpcwebproxy is listening,
    // or use nginx to serve the UI files, the API and gRPC all on the same port without CORS.
    private url: string
  ) {}

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
            reject(new GrpcError(res.status, res.statusMessage));
          }
        }
      });
    });
  }

  /** Get one of the blocks from the last rank. */
  getLatestBlockInfo(): Promise<BlockInfo> {
    return new Promise<BlockInfo>((resolve, reject) => {
      const request = new StreamBlockInfosRequest();
      request.setDepth(1);

      // For now just return any block, but ideally we should be looking at the fork choice tip.
      let resolved = false;

      grpc.invoke(GrpcCasperService.StreamBlockInfos, {
        host: this.url,
        request: request,
        onMessage: res => {
          if (!resolved) {
            resolved = true;
            resolve(res as BlockInfo);
          }
        },
        onEnd: (code, message) => {
          if (code !== grpc.Code.OK && !resolved) {
            reject(new GrpcError(code, message));
          }
        }
      });
    });
  }

  async getAccountBalance(
    blockHash: BlockHash,
    accountPublicKey: ByteArray
  ): Promise<number | null> {
    return 0;
  }
}
