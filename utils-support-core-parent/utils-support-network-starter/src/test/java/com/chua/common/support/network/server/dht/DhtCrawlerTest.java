package com.chua.common.support.network.server.dht;

import com.chua.common.support.network.server.dht.bep9.MetadataDownloader;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

/**
 * DHT 爬虫 + BEP 9 元数据下载演示。
 * <p>
 * 被动发现 infohash 后会主动向最近节点查询 BT peer，
 * 并通过 BEP 9 尝试下载种子名。
 * </p>
 */
class DhtCrawlerTest {

    @Test
    void crawl() throws Exception {
        DHTServer server = DHTServer.builder()
                .port(6882)
                .crawlListener(new DhtCrawlListener() {
                    @Override
                    public void onInfohash(String infohash, InetSocketAddress source) {
                        System.out.println("[被动发现] infohash=" + infohash + " 来自 " + source);
                    }
                    @Override
                    public void onPeers(String infohash, List<DhtPeer> peers) {
                        System.out.println("[BEP 9 下载] " + infohash + " -> " + peers.size() + " 个 BT 节点");
                        MetadataDownloader downloader = new MetadataDownloader();
                        byte[] infoHash = hexToBytes(infohash);
                        for (DhtPeer p : peers) {
                            MetadataDownloader.MetadataResult result = downloader.download(
                                    new InetSocketAddress(p.getHost(), p.getPort()),
                                    infoHash, 10_000);
                            if (result.ok) {
                                System.out.println("  *** 种子名: " + result.name + " ***");
                                System.out.println("  magnet:?xt=urn:btih:" + infohash + "&dn=" + result.name);
                                break;
                            }
                        }
                    }
                })
                .build();
        server.start();

        System.out.println("DHT crawler started. NodeId=" + server.selfId());
        System.out.println("等待引导和被动收集（最多 5 分钟）...");

        long start = System.currentTimeMillis();
        long duration = 300_000;

        while (System.currentTimeMillis() - start < duration) {
            Thread.sleep(5000);
            int total = server.protocol().routingTable().totalPeers();
            int ihCount = server.collectedInfohashes().size();
            System.out.printf("[%ds] 路由表: %d 节点 | infohash: %d%n",
                    elapsed(start), total, ihCount);
            if (total >= 30 && ihCount > 0) break;
        }

        // 主动迭代查找扩大网络可见性
        for (int i = 0; i < 3 && server.collectedInfohashes().isEmpty(); i++) {
            byte[] rand = new byte[20];
            java.util.concurrent.ThreadLocalRandom.current().nextBytes(rand);
            server.iterativeFindNode(KademliaNodeId.fromHex(bytesToHex(rand)));
            Thread.sleep(5000);
        }

        server.stop();
        Set<String> ihs = server.collectedInfohashes();
        System.out.println("---");
        System.out.println("路由表: " + server.protocol().routingTable().totalPeers() + " 节点");
        System.out.println("收集到 " + ihs.size() + " 个真实 infohash");
        ihs.forEach(ih -> System.out.println("  " + ih));
    }

    private static long elapsed(long start) { return (System.currentTimeMillis() - start) / 1000; }
    private static String bytesToHex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x & 0xff)); return sb.toString(); }
    private static byte[] hexToBytes(String h) { byte[] r = new byte[20]; for (int i = 0; i < 20; i++) r[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16); return r; }
}
