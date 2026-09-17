package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.Discovery;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * UDP scatter 远程客户端（无连接报文，通过 {@link ScatterSyncHelper} 统一处理重试/超时）。
 *
 * @author CH
 * @since 4.0.0.42
*/
@Slf4j
public class UdpScatterRemoteClient implements ScatterRemoteClient {

    @Override
    public ScatterResult<List<Discovery>> invoke(ScatterContext context, ScatterNode node, long timeoutMillis) {
        return ScatterSyncHelper.fetch(context, node, timeoutMillis);
    }

    @Override
    public void close() {
        // 无状态，无需释放
    }
}
