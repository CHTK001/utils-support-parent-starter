package com.chua.common.support.network.server.dht.bep9;

import java.net.InetSocketAddress;

/**
 * BEP 9 元数据下载器（最小骨架）。
 *
 * <p>用于 DHT 爬虫在被动发现 infohash 后，通过 BT peer-wire 协议
 * 拉取种子元数据以解析种子名称。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MetadataDownloader {

    /**
     * 下载结果。
     * @author CH
     * @since 4.0.0
     */
    public static class MetadataResult {
        /**
         * 是否下载成功
         */
        public boolean ok;
        /**
         * 解析到的种子名称
         */
        public String name;

        /** 创建 metadata结果 实例 */
        public MetadataResult() {
            this.ok = false;
            this.name = null;
        }

        /**
         * 创建 metadata结果 实例
         * @param ok ok
         * @param name 字符串
         * @param name 名称
         */
        public MetadataResult(boolean ok, String name) {
            this.ok = ok;
            this.name = name;
        }
    }

    /**
     * 构造元数据下载器。
     * @return MetadataDownloader的结果
     */
    public MetadataDownloader() {
    }

    /**
     * 执行元数据下载（骨架实现，未连接真实网络）。
     *
     * @param peer    对端节点地址
     * @param infoHash 信息哈希
     * @param timeoutMs 超时时间（毫秒）
     * @return 下载结果
     */
    public MetadataResult download(InetSocketAddress peer, byte[] infoHash, int timeoutMs) {
        // 骨架实现：未连接实际 BT peer-wire 协议，直接返回失败结果
        return new MetadataResult(false, null);
    }
}
