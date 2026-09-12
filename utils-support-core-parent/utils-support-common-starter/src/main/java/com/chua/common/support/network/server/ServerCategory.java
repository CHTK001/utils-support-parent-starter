package com.chua.common.support.network.server;


/**
* 服务器协议分类枚举，标识服务器所使用协议的核心用途类型。
*
* <ul>
*   <li>{@link #APPLICATION 应用型} — 请求-响应模式，客户端发起请求，服务端返回响应，如 HTTP、TCP</li>
*   <li>{@link #MESSAGE 消息型} — 发布-订阅或推送模式，服务端主动推送消息，如 WS、MQTT</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public enum ServerCategory {

    /**
    * 应用型协议。
    *
    * <p>基于请求-响应模式，客户端发起请求，服务端处理并返回响应。
    * 典型协议：HTTP、TCP。
     */
    APPLICATION,

    /**
    * 消息型协议。
    *
    * <p>基于发布-订阅或推送模式，服务端可主动向客户端推送消息。
    * 典型协议：WebSocket、MQTT。</p>
     */
    MESSAGE,

    /**
    * 文件型协议。
    *
    * <p>基于文件共享模式，客户端挂载远程文件系统，进行文件读写操作。
    * 典型协议：SMB、FTP、SFTP、WebDAV。</p>
     */
    FILE
}
