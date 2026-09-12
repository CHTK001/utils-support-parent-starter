package com.chua.nmap.support.scanner;

import com.chua.common.support.ai.bigmodel.BigModelClient;
import com.chua.common.support.ai.bigmodel.BigModelCallback;
import com.chua.common.support.ai.bigmodel.BigModelRequest;
import com.chua.common.support.ai.bigmodel.BigModelResponse;
import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.scanner.NetworkScanner;
import com.chua.nmap.support.NmapScanner;
import com.chua.nmap.support.RustNmapScanner;
import com.chua.nmap.support.bridge.RustNmapBridge;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 基于 Rust Nmap 的 NetworkScanner 实现，支持链式调用
 *
 * @author CH
 * @since 4.0.0.36
 */
@Slf4j
@Spi
public class NmapNetworkScanner implements NetworkScanner {

    private final String host;
    private final RustNmapScanner scanner;

    // 累积扫描的端口（去重）
    private final Set<Integer> portsToScan = new LinkedHashSet<>();
    // 扫描结果
    private final List<ScanPort> openPorts = new ArrayList<>();
    // 分析报告
    private ScanReport report;
    // AI 结论
    private String aiConclusion;
    // 主机存活信息
    private boolean alive;
    private long latencyMs;
    private long totalDuration;

    public NmapNetworkScanner(String host) {
        this.host = host;
        this.scanner = new RustNmapScanner();
    }

    @Override
    public NetworkScanner timeout(int timeoutMs) {
        scanner.setOptions(scanner.getOptions().setTimeout(timeoutMs));
        return this;
    }

    @Override
    public NetworkScanner concurrency(int concurrency) {
        scanner.setOptions(scanner.getOptions().setConcurrency(concurrency));
        return this;
    }

    @Override
    public NetworkScanner scanCommonPorts() {
        int[] ports = {21, 22, 23, 25, 53, 80, 110, 111, 135, 139, 143, 443, 445,
                993, 995, 1433, 1521, 2049, 3306, 3389, 5432, 5900, 6379, 8080, 8443, 27017};
        for (int p : ports) portsToScan.add(p);
        return doScan();
    }

    @Override
    public NetworkScanner scanWebPorts() {
        for (int p : new int[]{80, 443, 8080, 8443, 8888, 3000, 9090, 9200, 4443, 7443})
            portsToScan.add(p);
        return doScan();
    }

    @Override
    public NetworkScanner scanDbPorts() {
        for (int p : new int[]{3306, 5432, 6379, 27017, 1433, 2181, 9092, 5672, 15672, 9200, 7474})
            portsToScan.add(p);
        return doScan();
    }

    @Override
    public NetworkScanner scanAdminPorts() {
        for (int p : new int[]{22, 23, 3389, 5900, 5901, 2222, 2375, 2376, 6443})
            portsToScan.add(p);
        return doScan();
    }

    @Override
    public NetworkScanner scanPorts(int... ports) {
        for (int p : ports) portsToScan.add(p);
        return doScan();
    }

    @Override
    public NetworkScanner scanPortRange(int startPort, int endPort) {
        for (int p = startPort; p <= endPort; p++) portsToScan.add(p);
        return doScan();
    }

    /** 执行实际扫描（增量，只扫新增端口） */
    private NetworkScanner doScan() {
        if (portsToScan.isEmpty()) return this;

        long t = System.currentTimeMillis();

        // ping（只做一次）
        if (!alive && latencyMs == 0) {
            NmapScanner.HostInfo info = scanner.ping(host, scanner.getOptions().getTimeout());
            alive = info.isAlive();
            latencyMs = info.getLatency();
        }

        int[] ports = portsToScan.stream().mapToInt(Integer::intValue).toArray();
        NmapScanner.ScanResult result = scanner.scanTcpPorts(host, ports);
        totalDuration += System.currentTimeMillis() - t;

        // 合并结果（去重）
        Set<Integer> existing = openPorts.stream().map(ScanPort::port).collect(Collectors.toSet());
        for (NmapScanner.PortInfo p : result.getPorts()) {
            if (p.getState() == NmapScanner.PortState.OPEN && !existing.contains(p.getPort())) {
                String banner = null;
                if (p.getPort() == 80 || p.getPort() == 8080) {
                    try { banner = RustNmapBridge.getBanner(host, p.getPort(), 2000); } catch (Exception ignored) {}
                }
                openPorts.add(new ScanPort(p.getPort(), p.getProtocol(),
                        p.getServiceName() != null ? p.getServiceName() : "unknown", banner));
                existing.add(p.getPort());
            }
        }
        portsToScan.clear();
        return this;
    }

    @Override
    public NetworkScanner analyze() {
        List<RiskItem> risks = new ArrayList<>();
        for (ScanPort p : openPorts) {
            RiskItem risk = assessRisk(p);
            if (risk != null) risks.add(risk);
        }
        risks.sort(Comparator.comparing(r -> r.level().ordinal()));
        report = new ScanReport(host, alive, latencyMs, List.copyOf(openPorts), risks, totalDuration);

        // 内置简单结论（无 AI 时也能看到）
        buildBuiltinConclusion(risks);
        return this;
    }

    private void buildBuiltinConclusion(List<RiskItem> risks) {
        if (risks.isEmpty()) {
            aiConclusion = "[内置分析] 未发现明显安全风险，建议定期复查。";
            return;
        }
        StringBuilder sb = new StringBuilder("[内置安全分析]\n");
        long critical = risks.stream().filter(r -> r.level() == RiskLevel.CRITICAL).count();
        long high     = risks.stream().filter(r -> r.level() == RiskLevel.HIGH).count();
        long medium   = risks.stream().filter(r -> r.level() == RiskLevel.MEDIUM).count();

        // 整体评级
        String rating = critical > 0 ? "高危" : high > 0 ? "中高危" : medium > 0 ? "中危" : "低危";
        sb.append("整体安全评级: ").append(rating).append("\n");
        sb.append("风险统计: 严重=").append(critical).append(" 高危=").append(high)
                .append(" 中危=").append(medium).append("\n\n");

        // TOP 3 紧迫问题
        sb.append("最紧迫问题:\n");
        risks.stream().limit(3).forEach(r ->
                sb.append("  - [").append(r.level()).append("] 端口 ").append(r.port())
                        .append(": ").append(r.description()).append("\n"));

        // 综合建议
        sb.append("\n综合建议:\n");
        if (critical > 0) sb.append("  1. 立即处理严重风险，数据库/中间件不应对公网开放\n");
        if (high > 0)     sb.append("  2. 高危端口需限制访问来源，配置防火墙白名单\n");
        if (medium > 0)   sb.append("  3. 中危端口建议修改默认配置，启用强认证\n");
        sb.append("  4. 建议部署 WAF 和入侵检测系统\n");
        sb.append("  5. 定期进行安全扫描和渗透测试\n");
        sb.append("\n提示: 调用 analyzeWithAi(BigModelClient) 可获取 AI 深度分析");

        aiConclusion = sb.toString();
    }

    private RiskItem assessRisk(ScanPort p) {
        return switch (p.port()) {
            case 22 -> new RiskItem(RiskLevel.MEDIUM, 22, "SSH",
                    "SSH 服务对外开放，存在暴力破解风险",
                    "限制来源 IP，禁用密码登录，使用密钥认证，修改默认端口");
            case 23 -> new RiskItem(RiskLevel.CRITICAL, 23, "Telnet",
                    "Telnet 明文传输，极高安全风险",
                    "立即关闭 Telnet，改用 SSH");
            case 21 -> new RiskItem(RiskLevel.HIGH, 21, "FTP",
                    "FTP 明文传输，存在凭证泄露风险",
                    "改用 SFTP/FTPS，或关闭 FTP");
            case 3306 -> new RiskItem(RiskLevel.CRITICAL, 3306, "MySQL",
                    "MySQL 数据库端口对外暴露，存在数据泄露和注入风险",
                    "绑定到 127.0.0.1，通过防火墙限制访问，禁止 root 远程登录");
            case 5432 -> new RiskItem(RiskLevel.CRITICAL, 5432, "PostgreSQL",
                    "PostgreSQL 数据库端口对外暴露",
                    "绑定到 127.0.0.1，配置 pg_hba.conf 限制访问");
            case 6379 -> new RiskItem(RiskLevel.CRITICAL, 6379, "Redis",
                    "Redis 无认证对外暴露，可被利用执行任意命令",
                    "设置 requirepass，绑定到内网 IP，禁止公网访问");
            case 27017 -> new RiskItem(RiskLevel.CRITICAL, 27017, "MongoDB",
                    "MongoDB 对外暴露，默认无认证",
                    "启用认证，绑定到内网 IP");
            case 2181 -> new RiskItem(RiskLevel.HIGH, 2181, "ZooKeeper",
                    "ZooKeeper 对外暴露，可被未授权访问",
                    "配置 ACL，限制访问来源");
            case 9092 -> new RiskItem(RiskLevel.HIGH, 9092, "Kafka",
                    "Kafka Broker 对外暴露，可能被未授权消费/生产",
                    "配置 SASL 认证，限制访问来源");
            case 3389 -> new RiskItem(RiskLevel.HIGH, 3389, "RDP",
                    "RDP 远程桌面对外开放，存在暴力破解和漏洞利用风险",
                    "限制来源 IP，启用 NLA，修改默认端口");
            case 5900, 5901 -> new RiskItem(RiskLevel.HIGH, p.port(), "VNC",
                    "VNC 远程桌面对外开放，存在暴力破解风险",
                    "设置强密码，限制来源 IP，通过 SSH 隧道访问");
            case 2375 -> new RiskItem(RiskLevel.CRITICAL, 2375, "Docker API",
                    "Docker 未加密 API 对外暴露，可获取宿主机 root 权限",
                    "立即关闭或改用 TLS 加密的 2376 端口");
            case 1433 -> new RiskItem(RiskLevel.CRITICAL, 1433, "MSSQL",
                    "SQL Server 对外暴露",
                    "限制访问来源，禁用 SA 账户，使用 Windows 认证");
            case 80 -> new RiskItem(RiskLevel.INFO, 80, "HTTP",
                    "HTTP 服务开放" + (p.banner() != null ? "，服务器: " + extractServer(p.banner()) : ""),
                    "建议启用 HTTPS，隐藏服务器版本信息");
            case 443 -> new RiskItem(RiskLevel.INFO, 443, "HTTPS",
                    "HTTPS 服务开放",
                    "确保 TLS 版本 >= 1.2，定期更新证书");
            default -> p.port() >= 8080 && p.port() <= 8090
                    ? new RiskItem(RiskLevel.LOW, p.port(), p.service(), "非标准 Web 端口开放", "确认是否必要对外暴露")
                    : null;
        };
    }

    private String extractServer(String banner) {
        if (banner == null) return "";
        for (String line : banner.split("\r?\n")) {
            if (line.toLowerCase().startsWith("server:")) {
                return line.substring(7).trim();
            }
        }
        return "";
    }

    @Override
    public NetworkScanner analyzeWithAi(BigModelClient client) {
        if (report == null) analyze();
        String p = buildAiPrompt();
        BigModelRequest req = BigModelRequest.builder().prompt(p).build();
        StringBuilder sb = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        client.callStream(req, new BigModelCallback() {
            @Override
            public void accept(BigModelResponse response) {
                if (response.getOutput() != null) sb.append(response.getOutput());
                if (response.isDone()) latch.countDown();
            }
            @Override
            public void exception(Throwable e) {
                sb.append("[AI分析失败: ").append(e.getMessage()).append("]");
                latch.countDown();
            }
            @Override
            public void onComplete() { latch.countDown(); }
        });
        try { latch.await(60, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        aiConclusion = sb.toString();
        return this;
    }

    @Override
    public NetworkScanner analyzeWithAi(BigModelClient client, Consumer<String> callback) {
        if (report == null) analyze();
        String p2 = buildAiPrompt();
        BigModelRequest req2 = BigModelRequest.builder().prompt(p2).build();
        StringBuilder sb = new StringBuilder();
        client.callStream(req2, new BigModelCallback() {
            @Override
            public void accept(BigModelResponse response) {
                if (response.getOutput() != null) {
                    sb.append(response.getOutput());
                    callback.accept(response.getOutput());
                }
            }
            @Override
            public void exception(Throwable e) {
                aiConclusion = "[AI分析失败: " + e.getMessage() + "]";
            }
            @Override
            public void onComplete() { aiConclusion = sb.toString(); }
        });
        return this;
    }

    private String buildAiPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一名网络安全专家，请对以下网络扫描结果进行安全分析，给出专业的安全评估报告。\n\n");
        sb.append("## 扫描目标\n").append(host).append("\n\n");
        sb.append("## 主机状态\n存活: ").append(alive ? "是" : "否")
                .append("，延迟: ").append(latencyMs).append("ms\n\n");
        sb.append("## 开放端口\n");
        for (ScanPort p : openPorts) {
            sb.append("- ").append(p.port()).append("/").append(p.protocol())
                    .append(" (").append(p.service()).append(")");
            if (p.banner() != null && !p.banner().isBlank()) {
                sb.append(" Banner: ").append(p.banner(), 0, Math.min(100, p.banner().length()));
            }
            sb.append("\n");
        }
        sb.append("\n## 初步风险识别\n");
        if (report != null) {
            for (RiskItem r : report.risks()) {
                sb.append("- [").append(r.level()).append("] 端口 ").append(r.port())
                        .append(" (").append(r.service()).append("): ").append(r.description()).append("\n");
            }
        }
        sb.append("\n请从以下维度给出分析：\n");
        sb.append("1. 整体安全评级（高危/中危/低危）\n");
        sb.append("2. 最紧迫的安全问题（TOP 3）\n");
        sb.append("3. 具体修复建议\n");
        sb.append("4. 是否存在已知漏洞利用风险\n");
        sb.append("5. 综合安全加固建议\n");
        return sb.toString();
    }

    @Override
    public List<ScanPort> openPorts() {
        return Collections.unmodifiableList(openPorts);
    }

    @Override
    public ScanReport report() {
        if (report == null) analyze();
        return report;
    }

    @Override
    public String aiConclusion() {
        return aiConclusion;
    }

    @Override
    public NetworkScanner print() {
        ScanReport r = report();
        String box = "=".repeat(60);
        String sep = "-".repeat(60);
        System.out.println(box);
        System.out.println("  网络安全扫描报告");
        System.out.println(sep);
        System.out.println("  " + r.summary());
        System.out.println();

        if (!r.openPorts().isEmpty()) {
            System.out.println("【开放端口】");
            r.openPorts().forEach(p -> System.out.println("  " + p));
            System.out.println();
        }

        if (!r.risks().isEmpty()) {
            System.out.println("【安全风险】");
            for (RiskItem risk : r.risks()) {
                String level = switch (risk.level()) {
                    case CRITICAL -> "[CRITICAL]";
                    case HIGH     -> "[HIGH    ]";
                    case MEDIUM   -> "[MEDIUM  ]";
                    case LOW      -> "[LOW     ]";
                    case INFO     -> "[INFO    ]";
                };
                System.out.println("  " + level + " 端口 " + risk.port() + " (" + risk.service() + ")");
                System.out.println("    问题: " + risk.description());
                System.out.println("    建议: " + risk.recommendation());
            }
            System.out.println();
        }

        if (aiConclusion != null && !aiConclusion.isBlank()) {
            System.out.println("【AI 安全分析】");
            System.out.println(aiConclusion);
        }

        System.out.println(box);
        return this;
    }
}
