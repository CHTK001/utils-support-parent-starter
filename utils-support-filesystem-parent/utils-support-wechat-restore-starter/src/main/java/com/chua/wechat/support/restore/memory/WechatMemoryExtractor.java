package com.chua.wechat.support.restore.memory;

import com.chua.wechat.support.restore.keyscan.WechatMemoryKeyScanner;
import lombok.extern.slf4j.Slf4j;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 微信进程内存明文页提取器。
 *
 * <p>从微信进程的私有内存里找出 SQLCipher 解密后留在 pager cache 里的明文页，
 * 解析成记录并按还原出的 schema 做表归属与「库簇」归属。</p>
 *
 * <h3>必须扫描<b>所有</b> Weixin.exe（最容易踩的坑）</h3>
 *
 * <p>微信是多进程架构，消息数据可能落在任意一个 {@code Weixin.exe} 里，
 * 而且<b>跟内存大小没有关系</b>。实测（微信 4.1.13.65）：</p>
 * <pre>
 * pid  8792  工作集 700MB  消息   54 条   ← 内存最大，却只有零星会话
 * pid 23176  工作集 366MB  消息  398 条   ← 真正持有大量消息
 * </pre>
 *
 * <p>只扫「内存最大的那个进程」会得出「只还原出 54 条」的错误结论。
 * 本类默认枚举全部同名进程并逐个扫描后合并。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WechatMemoryExtractor {

    /**
     * 单块读取的最大字节数
     */
    private static final int CHUNK = 8 << 20;

    /**
     * 单区域读取上限（128MB），超过则跳过 —— 超大区域通常是共享内存而非 pager cache
     */
    private static final long MAX_REGION_BYTES = 128L << 20;

    /**
     * 同一库的 Name2Id 叶子页地址上限（页由同一 pager cache 分配，物理相邻）
     */
    private static final long CLUSTER_DISTANCE = 2L << 20;

    private WechatMemoryExtractor() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 单个进程的扫描统计。
     *
     * @param pid           进程号
     * @param workingSetBytes 工作集大小
     * @param readableBytes 实际读到的字节数
     * @param strictPages   严格页数
     * @param lenientPages  宽松页数
     * @param recordCount   解析出的记录数
     */
    public record ProcessScan(int pid, long workingSetBytes, long readableBytes,
                              int strictPages, int lenientPages, int recordCount) {
    }

    /**
     * 提取到的一条记录。
     *
     * @param pid          来源进程
     * @param pageAddress  来源页地址
     * @param table        归属表名（{@code null} 表示无法判定）
     * @param rowid        行号
     * @param values       各列值
     * @param serialTypes  序列类型
     */
    public record ExtractedRecord(int pid, long pageAddress, String table,
                                  long rowid, String[] values, int[] serialTypes) {
    }

    /**
     * Name2Id 库簇。
     *
     * <p>一个库的 Name2Id 表行数超过一页容量时会被 SQLite 分裂成多个叶子页，
     * 必须按「地址相邻 + id 空间不冲突」合并，否则一张表会被当成两个库。</p>
     *
     * @param pid         进程号
     * @param pageAddress 代表页地址
     * @param pages       组成该簇的全部页地址
     * @param idMap       id → username
     */
    public record IdCluster(int pid, long pageAddress, List<Long> pages, Map<Integer, String> idMap) {
    }

    /**
     * 提取结果。
     *
     * @param processes 各进程扫描统计
     * @param records   全部记录（跨进程已按 rowid + 内容去重）
     * @param schemas   还原出的表结构
     * @param clusters  Name2Id 库簇
     */
    public record ExtractResult(List<ProcessScan> processes, List<ExtractedRecord> records,
                                Map<String, WechatMemoryPageParser.TableSchema> schemas,
                                List<IdCluster> clusters) {
    }

    /**
     * 列出所有微信进程（按工作集降序）。
     *
     * @return 进程列表
     */
    public static List<WechatMemoryKeyScanner.PidInfo> listProcesses() {
        return WechatMemoryKeyScanner.weixinProcesses();
    }

    /**
     * 扫描全部微信进程并提取明文页记录。
     *
     * @param decodeBlob 是否解压 zstd 压缩的消息体
     * @return 提取结果
     */
    public static ExtractResult extract(boolean decodeBlob) {
        List<WechatMemoryKeyScanner.PidInfo> processes = listProcesses();
        List<Integer> pids = new ArrayList<>(processes.size());
        for (WechatMemoryKeyScanner.PidInfo info : processes) {
            pids.add(info.pid());
        }
        return extract(pids, processes, decodeBlob);
    }

    /**
     * 扫描指定进程并提取明文页记录。
     *
     * @param pids       进程号列表
     * @param processes  进程信息（可为 null，仅用于回填工作集）
     * @param decodeBlob 是否解压 zstd 压缩的消息体
     * @return 提取结果
     */
    public static ExtractResult extract(Collection<Integer> pids,
                                        List<WechatMemoryKeyScanner.PidInfo> processes,
                                        boolean decodeBlob) {
        ExtractResult[] holder = new ExtractResult[1];
        try {
            extractEach(pids, processes, decodeBlob, result -> holder[0] = result);
        } catch (Exception e) {
            throw new IllegalStateException("扫描微信进程内存失败: " + e.getMessage(), e);
        }
        return holder[0];
    }

    /**
     * 逐进程扫描，<b>每扫完一个进程就回调一次</b>。
     *
     * <h3>为什么要逐进程回调</h3>
     *
     * <p>内存扫描会被外部<b>随机杀掉</b>（本机实测 8 次里有 3 次：进程直接消失，
     * 没有异常、没有 {@code hs_err}、连导出后的最后一行日志都可能丢）。
     * 一次扫全部进程再统一落盘的话，被杀就全丢。改成每扫完一个进程回调一次，
     * 调用方就能在回调里把结果<b>立刻落盘</b>，被打断最多损失当前这一个进程。</p>
     *
     * <p>回调收到的是「到目前为止」的完整视图（含之前所有进程的记录，且已用当前
     * 能建出来的最全 schema 重新做过表归属），不是增量。</p>
     *
     * @param pids       进程号列表
     * @param processes  进程信息（可为 null，仅用于回填工作集）
     * @param decodeBlob 是否解压 zstd 压缩的消息体
     * @param sink       每个进程扫描完成后的回调
     * @throws Exception 回调抛出的异常
     */
    public static void extractEach(Collection<Integer> pids,
                                   List<WechatMemoryKeyScanner.PidInfo> processes,
                                   boolean decodeBlob,
                                   ProcessSink sink) throws Exception {
        Map<Integer, Long> workingSets = new LinkedHashMap<>();
        if (processes != null) {
            for (WechatMemoryKeyScanner.PidInfo info : processes) {
                workingSets.put(info.pid(), info.workingSetBytes());
            }
        }

        List<ProcessScan> scans = new ArrayList<>();
        List<ExtractedRecord> collected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean scanned = false;
        for (int pid : pids) {
            ProcessScan scan = scanProcess(pid, workingSets.getOrDefault(pid, 0L), decodeBlob,
                    collected, seen);
            scans.add(scan);
            scanned = true;
            log.info("进程 {} 扫描完成: 可读 {}MB / 严格页 {} / 宽松页 {} / 记录 {}",
                    pid, scan.readableBytes() >> 20, scan.strictPages(),
                    scan.lenientPages(), scan.recordCount());
            sink.accept(finish(collected, scans));
        }
        if (!scanned) {
            // 一个进程都没扫到也要回调一次，让调用方拿到「空结果」而不是 null
            sink.accept(finish(collected, scans));
        }
    }

    /**
     * 进程扫描回调。
     */
    @FunctionalInterface
    public interface ProcessSink {
        /**
         * 处理一个进程扫描后的完整视图。
         *
         * @param result 到目前为止的提取结果
         * @throws Exception 处理失败
         */
        void accept(ExtractResult result) throws Exception;
    }

    /**
     * 对已收集的记录做表归属与库簇构建，产出结果。
     *
     * @param collected 已收集的原始记录（表名为 null）
     * @param scans     各进程扫描统计
     * @return 提取结果
     */
    private static ExtractResult finish(List<ExtractedRecord> collected, List<ProcessScan> scans) {
        Map<String, WechatMemoryPageParser.TableSchema> schemas =
                WechatMemoryPageParser.buildSchema(recordsOf(collected));
        List<ExtractedRecord> attributed = new ArrayList<>(collected.size());
        for (ExtractedRecord record : collected) {
            String table = WechatMemoryPageParser.attribute(
                    new WechatMemoryPageParser.LeafRecord(record.rowid(), record.values(),
                            record.serialTypes()), schemas);
            // 「某张 Msg_ 表」在重建库时统一落到一张便于查询的表上
            if ("Msg_*".equals(table)) {
                table = WechatMemoryRebuilder.MSG_TABLE;
            }
            attributed.add(new ExtractedRecord(record.pid(), record.pageAddress(), table,
                    record.rowid(), record.values(), record.serialTypes()));
        }
        return new ExtractResult(new ArrayList<>(scans), attributed, schemas,
                buildClusters(attributed));
    }

    /**
     * 从记录集重新解析表结构。
     *
     * <p>用于跨次扫描累积之后重建 schema：{@code sqlite_master} 的建表语句本身就是普通记录，
     * 被一起累积了，所以直接从合并后的记录里再解析一遍即可。</p>
     *
     * @param records 记录集
     * @return 表名 → 表结构
     */
    static Map<String, WechatMemoryPageParser.TableSchema> rebuildSchema(List<ExtractedRecord> records) {
        return WechatMemoryPageParser.buildSchema(recordsOf(records));
    }

    /**
     * 把提取记录转成解析器记录（用于建 schema）。
     *
     * @param records 提取记录
     * @return 解析器记录
     */
    private static List<WechatMemoryPageParser.LeafRecord> recordsOf(List<ExtractedRecord> records) {
        List<WechatMemoryPageParser.LeafRecord> out = new ArrayList<>(records.size());
        for (ExtractedRecord record : records) {
            out.add(new WechatMemoryPageParser.LeafRecord(record.rowid(), record.values(),
                    record.serialTypes()));
        }
        return out;
    }

    /**
     * 扫描单个进程。
     *
     * @param pid        进程号
     * @param workingSet 工作集
     * @param decodeBlob 是否解压 blob
     * @param collected  输出：收集到的记录
     * @param seen       输出：已见过的记录指纹（跨进程去重）
     * @return 扫描统计
     */
    private static ProcessScan scanProcess(int pid, long workingSet, boolean decodeBlob,
                                           List<ExtractedRecord> collected, Set<String> seen) {
        int strict = 0;
        int lenient = 0;
        int count = 0;
        long readable = 0;
        Set<String> pageHashes = new HashSet<>();
        try (WechatMemoryAccess access = WechatMemoryAccess.open(pid)) {
            if (access == null) {
                return new ProcessScan(pid, workingSet, 0, 0, 0, 0);
            }
            for (WechatMemoryAccess.Region region : access.regions()) {
                // 只取私有内存：pager cache 分配在堆上；映射区是 DLL 与共享内存
                if (!region.privateMem() || region.size() > MAX_REGION_BYTES) {
                    continue;
                }
                byte[] buffer = access.read(region.base(), (int) region.size());
                if (buffer == null || buffer.length < WechatMemoryPageParser.PAGE_SIZE) {
                    continue;
                }
                readable += buffer.length;
                for (WechatMemoryPageParser.LeafPage page
                        : WechatMemoryPageParser.scan(buffer, region.base(), decodeBlob)) {
                    // 同一页可能有多份 cache 副本，按内容哈希去重
                    if (!pageHashes.add(sha1(buffer, (int) (page.address() - region.base())))) {
                        continue;
                    }
                    if (page.strict()) {
                        strict++;
                    } else {
                        lenient++;
                    }
                    for (WechatMemoryPageParser.LeafRecord record : page.records()) {
                        String fingerprint = record.rowid() + "\u0001" + String.join("\u0001", record.values());
                        if (!seen.add(fingerprint)) {
                            continue;
                        }
                        collected.add(new ExtractedRecord(pid, page.address(), null,
                                record.rowid(), record.values(), record.serialTypes()));
                        count++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("扫描进程 {} 失败: {}", pid, e.getMessage());
        }
        return new ProcessScan(pid, workingSet, readable, strict, lenient, count);
    }

    /**
     * 构建 Name2Id 库簇。
     *
     * @param records 全部记录
     * @return 库簇列表
     */
    static List<IdCluster> buildClusters(List<ExtractedRecord> records) {
        Map<String, Map<Integer, String>> pages = new LinkedHashMap<>();
        for (ExtractedRecord record : records) {
            // 表名大小写不敏感：微信不同库里同一张表可能写作 Name2Id / name2id
            if (record.table() == null || !"name2id".equalsIgnoreCase(record.table())
                    || record.values().length == 0 || record.values().length > 2) {
                continue;
            }
            // 微信 4.x 里同时存在两种形态，两者都是「rowid → 用户名」：
            //   CREATE TABLE name2id(username TEXT PRIMARY KEY)                          ← 1 列
            //   CREATE TABLE Name2Id(user_name TEXT PRIMARY KEY, is_session INTEGER)     ← 2 列
            // 只认 2 列会把 1 列那份整个丢掉（实测某个进程 8323 条全被过滤，导致该进程
            // 一条消息都解析不出发送者）。列数不做区分，统一取第一列作用户名、rowid 作 id。
            String name = record.values()[0] == null ? "" : record.values()[0].trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                pages.computeIfAbsent(record.pid() + "#" + record.pageAddress(),
                        k -> new LinkedHashMap<>()).put(Integer.parseInt(Long.toString(record.rowid())), name);
            } catch (NumberFormatException ignored) {
                // rowid 非数字，跳过
            }
        }

        List<String> keys = new ArrayList<>(pages.keySet());
        keys.sort(Comparator.comparingLong(WechatMemoryExtractor::addressOf));
        List<IdCluster> clusters = new ArrayList<>();
        for (String key : keys) {
            Map<Integer, String> idMap = pages.get(key);
            if (idMap.values().stream().noneMatch(WechatMemoryExtractor::looksLikeUser)) {
                // MessageResourceInfo / MessageResourceDetail 这类 2 列表会被误归属为 Name2Id
                continue;
            }
            int pid = pidOf(key);
            long address = addressOf(key);
            IdCluster target = null;
            for (IdCluster cluster : clusters) {
                if (cluster.pid() != pid) {
                    continue;
                }
                long distance = Long.MAX_VALUE;
                for (long page : cluster.pages()) {
                    distance = Math.min(distance, Math.abs(page - address));
                }
                if (distance > CLUSTER_DISTANCE) {
                    continue;
                }
                // 不同库的 Name2Id 都从 id=1 开始（每库都有「自己」），id 重叠即异库
                Set<Integer> intersection = new LinkedHashSet<>(cluster.idMap().keySet());
                intersection.retainAll(idMap.keySet());
                if (!intersection.isEmpty()) {
                    continue;
                }
                target = cluster;
                break;
            }
            if (target == null) {
                List<Long> pageList = new ArrayList<>();
                pageList.add(address);
                clusters.add(new IdCluster(pid, address, pageList, new LinkedHashMap<>(idMap)));
            } else {
                target.idMap().putAll(idMap);
                target.pages().add(address);
            }
        }
        return mergeContiguous(clusters);
    }

    /**
     * 合并 id 空间互补的库簇。
     *
     * <p>同一张 Name2Id 表会被 SQLite 切成多个叶子页，而这些页可能被<b>不同进程</b>各自缓存一部分
     * （微信多进程架构下，同一张表的页会散落在多个 {@code Weixin.exe} 的 pager cache 里）。
     * 实测四个簇的 id 空间为 {@code 1..2623} / {@code 2624..3798} / {@code 3799..3964} /
     * {@code 3965..5886}，<b>完美拼接且互不重叠</b> —— 它们其实是同一张表。</p>
     *
     * <p>合并判据（两条同时满足，误合并概率极低）：</p>
     * <ol>
     *   <li>两个簇的 id 空间<b>完全不重叠</b>（每库的 id 都从 1 开始，重叠即异库）；</li>
     *   <li>合并后的 id 集合<b>恰好是 {@code 1..max} 且无空洞</b>（数量等于最大值）。</li>
     * </ol>
     *
     * <p>地址距离在这里<b>不作判据</b>：实测同一库的页可以相距 190MB 以上，
     * 而相距不到 1MB 的页却分属两个库 —— 地址会交错，id 空间不会骗人。</p>
     *
     * @param clusters 地址邻近归并后的簇
     * @return 合并后的簇
     */
    static List<IdCluster> mergeContiguous(List<IdCluster> clusters) {
        List<IdCluster> work = new ArrayList<>(clusters);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < work.size() && !changed; i++) {
                for (int j = i + 1; j < work.size(); j++) {
                    IdCluster first = work.get(i);
                    IdCluster second = work.get(j);
                    Set<Integer> union = new HashSet<>(first.idMap().keySet());
                    union.addAll(second.idMap().keySet());
                    if (union.size() != first.idMap().size() + second.idMap().size()) {
                        continue;
                    }
                    int max = 0;
                    for (Integer id : union) {
                        max = Math.max(max, id);
                    }
                    if (max != union.size()) {
                        continue;
                    }
                    first.idMap().putAll(second.idMap());
                    first.pages().addAll(second.pages());
                    work.remove(j);
                    changed = true;
                    break;
                }
            }
        }
        return work;
    }

    /**
     * 判断名字是否像用户标识（用于过滤误归属的 2 列表）。
     *
     * @param name 名字
     * @return 像用户标识返回 true
     */
    private static boolean looksLikeUser(String name) {
        if (name == null) {
            return false;
        }
        return name.startsWith("wxid_") || name.endsWith("@chatroom") || name.endsWith("@openim")
                || name.startsWith("gh_") || "filehelper".equals(name) || "weixin".equals(name)
                || "notifymessage".equals(name) || "medianote".equals(name);
    }

    /**
     * 解析页标识里的进程号。
     *
     * @param key 页标识
     * @return 进程号
     */
    private static int pidOf(String key) {
        int at = key.indexOf('#');
        return at < 0 ? 0 : Integer.parseInt(key.substring(0, at));
    }

    /**
     * 解析页标识里的地址。
     *
     * @param key 页标识
     * @return 地址
     */
    private static long addressOf(String key) {
        int at = key.indexOf('#');
        return at < 0 ? 0 : Long.parseLong(key.substring(at + 1));
    }

    /**
     * 计算缓冲区片段的 SHA-1。
     *
     * @param buffer 缓冲
     * @param offset 偏移
     * @return 十六进制摘要
     */
    private static String sha1(byte[] buffer, int offset) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(buffer, offset,
                    Math.min(WechatMemoryPageParser.PAGE_SIZE, buffer.length - offset));
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
