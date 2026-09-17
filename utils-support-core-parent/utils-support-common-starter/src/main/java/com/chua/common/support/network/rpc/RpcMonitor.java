package com.chua.common.support.network.rpc;

import java.util.Collections;
import java.util.List;

/**
* RPC 监控 SPI 接口，为各协议 RPC 服务端提供统一的运行状态查询能力。
*
* <p>所有方法提供默认实现（返回空数据），协议实现可按能力覆盖：
* Dubbo 可返回真实连接数/服务数，JsonRpcServer 可返回请求计数与来源地址，
* SOFA 可返回已导出服务数等。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface RpcMonitor {

    /**
    * 获取当前所有连接信息。
    *
    * @return 连接信息列表，无连接或实现不支持时返回空列表
    */
    default List<RpcConnectionInfo> getConnections() {
        return Collections.emptyList();
    }

    /**
    * 获取运行时指标快照。
    *
    * @return 指标快照，实现不支持时返回全零快照
    */
    default RpcMetrics getMetrics() {
        return RpcMetrics.immutable(getProtocol());
    }

    /**
    * 获取协议名称。
    *
    * @return 协议名称
    */
    default String getProtocol() {
        return "unknown";
    }

    /**
    * 获取已暴露的服务数量。
    *
    * @return 服务数量
    */
    default int getServiceCount() {
        return 0;
    }
}
