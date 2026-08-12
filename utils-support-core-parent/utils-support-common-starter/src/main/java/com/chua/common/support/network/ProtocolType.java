package com.chua.common.support.network;

import com.chua.common.support.network.server.ServerCategory;
import lombok.Getter;

/**
 * 协议类型枚举，用于区分不同类型的网络协议。
 * <p>
 * 协议分类说明：
 * <table border="1">
 *   <tr>
 *     <th>类别</th>
 *     <th>枚举值</th>
 *     <th>通信模式</th>
 *     <th>典型应用场景</th>
 *   </tr>
 *   <tr>
 *     <td>应用型</td>
 *     <td>HTTP、TCP、UDP</td>
 *     <td>请求-响应</td>
 *     <td>Web 服务、代理转发、DNS 查询</td>
 *   </tr>
 *   <tr>
 *     <td>消息型</td>
 *     <td>WS、MQTT</td>
 *     <td>发布-订阅/推送</td>
 *     <td>实时通信、物联网遥测数据上报</td>
 *   </tr>
 *   <tr>
 *     <td>文件型</td>
 *     <td>FTP、SFTP</td>
 *     <td>会话交互</td>
 *     <td>文件传输、远程服务器访问</td>
 *   </tr>
 * </table>
 *
 * @author CH
 * @version 2.0
 * @since 2026/07/16
 */
@Getter
public enum ProtocolType {

    /**
     * HTTP 协议，基于请求 - 响应的应用层协议。
     */
    HTTP(ServerCategory.APPLICATION),

    /**
     * TCP 协议，面向连接的可靠传输应用层协议。
     */
    TCP(ServerCategory.APPLICATION),
    UDP(ServerCategory.APPLICATION),
    KCP(ServerCategory.MESSAGE),
    WS(ServerCategory.MESSAGE),
    MQTT(ServerCategory.MESSAGE),
    RSOCKET(ServerCategory.MESSAGE),
    SOCKET_IO(ServerCategory.MESSAGE),
    FTP(ServerCategory.APPLICATION),
    SFTP(ServerCategory.APPLICATION),
    IPC(ServerCategory.APPLICATION),
    SSH(ServerCategory.APPLICATION),
    SMB(ServerCategory.APPLICATION),
    UNKNOWN(ServerCategory.APPLICATION);

    /**
     * 协议所属的服务类别，如应用层或消息层。
     * -- GETTER --
     *  获取该协议所属的服务类别。
     *
     * @return 服务类别枚举值。

     */
    private final ServerCategory category;

    /**
     * 构造函数，初始化协议类型及其对应的服务类别。
     *
     * @param category 服务类别，由 {@link ServerCategory} 定义。
     */
    ProtocolType(final ServerCategory category) {
        this.category = category;
    }

    /**
     * 判断当前协议是否属于消息型协议。
     *
     * @return 如果是消息型协议返回 true，否则返回 false。
     */
    public boolean isMessage() {
        return category == ServerCategory.MESSAGE;
    }

    /**
     * 判断当前协议是否属于应用型协议。
     *
     * @return 如果是应用型协议返回 true，否则返回 false。
     */
    public boolean isApplication() {
        return category == ServerCategory.APPLICATION;
    }

    /**
     * 根据字符串名称解析对应的协议类型枚举。
     *
     * @param name 协议类型的字符串名称。
     * @return 匹配的协议类型枚举，如果未匹配则返回 {@link #UNKNOWN}。
     */
    public static ProtocolType fromName(final String name) {
        if (name == null) {
            return UNKNOWN;
        }
        for (ProtocolType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}