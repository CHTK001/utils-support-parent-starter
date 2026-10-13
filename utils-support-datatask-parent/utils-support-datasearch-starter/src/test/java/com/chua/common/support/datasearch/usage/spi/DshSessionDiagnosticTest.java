package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.lang.json.Json;
import com.github.luben.zstd.ZstdInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSH 会话转录的<b>现场诊断</b>（非断言型回归）。
 *
 * <p>DSH 的转录是 zstd 压缩的 {@code session.v4.jsonl.zstd}，字段口径只能从真实数据反推，
 * 而单测夹具写不出"本机真实分布"。本类在本机存在 DSH 数据时解压并统计事件类型，
 * 用于人工核对解析口径是否仍与真实数据一致；无数据时自动跳过，不影响 CI。</p>
 *
 * <p>只读：不写入、不修改任何文件。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
class DshSessionDiagnosticTest {

    /**
     * 解析 DSH 会话根目录。
     *
     * <p>刻意<b>延迟</b>到调用时求值：同 JVM 内其它测试会把 {@code user.home}
     * 改到假主目录，类加载时固化的路径会跟着失真。</p>
     *
     * @return 会话根目录
     */
    private static Path dshRoot() {
        String home = System.getenv("DSH_HOME");
        if (home == null || home.isBlank()) {
            home = System.getenv("TOKENTRACKER_DSH_HOME");
        }
        Path root = (home != null && !home.isBlank())
                ? Path.of(home)
                : Path.of(System.getProperty("user.home"), ".dsh");
        return root.resolve("sessions");
    }

    /**
     * 本机是否存在 DSH 会话数据。
     *
     * @return 存在返回 true
     */
    static boolean hasDshData() {
        return Files.isDirectory(dshRoot());
    }

    @Test
    @EnabledIf("hasDshData")
    void reportsEventShape() throws Exception {
        Path DSH_ROOT = dshRoot();
        int files = 0;
        int totalLines = 0;
        try (Stream<Path> s = Files.walk(DSH_ROOT)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                files++;
                byte[] plain;
                try (InputStream in = maybeZstd(Files.readAllBytes(p))) {
                    plain = in.readAllBytes();
                }
                Map<String, Integer> types = new LinkedHashMap<>();
                int lines = 0;
                int usageLines = 0;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        new ByteArrayInputStream(plain), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        lines++;
                        if (line.isBlank()) {
                            continue;
                        }
                        String type;
                        try {
                            type = Json.parse(line).get("type").toStringValue();
                        } catch (Exception e) {
                            continue;
                        }
                        if (type == null) {
                            type = "?";
                        }
                        types.merge(type, 1, Integer::sum);
                        if (line.contains("usage")) {
                            usageLines++;
                        }
                    }
                }
                totalLines += lines;
                System.out.println("[dsh-diag] " + p.getFileName()
                        + " lines=" + lines + " usageLines=" + usageLines + " types=" + types);
            }
        }
        System.out.println("[dsh-diag] files=" + files + " totalLines=" + totalLines);
        assertTrue(files >= 0);
    }

    /**
     * 按 zstd 魔数判断是否压缩，是则解压。
     *
     * @param raw 原始字节
     * @return 明文输入流
     * @throws Exception 解压失败
     */
    private static InputStream maybeZstd(byte[] raw) throws Exception {
        boolean zstd = raw.length >= 4
                && (raw[0] & 0xFF) == 0x28 && (raw[1] & 0xFF) == 0xB5
                && (raw[2] & 0xFF) == 0x2F && (raw[3] & 0xFF) == 0xFD;
        return zstd ? new ZstdInputStream(new ByteArrayInputStream(raw))
                : new ByteArrayInputStream(raw);
    }
}
