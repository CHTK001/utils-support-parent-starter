// [P3C A20 豁免] A20/A30/A31: 含日志/断言字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.datasearch.example;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link BaseUsageParser} 多目录解析夹具自检示例。
 *
 * <p>本模块没有 JUnit 依赖，回归用例沿用 {@code exec-maven-plugin} 的示例 main 方式：
 * 在临时目录合成两个可用解析目录与一个必然失败的目录，逐项核对多目录口径；
 * 任一断言失败即抛异常，让 {@code exec:java} 以非零构建结果收场。</p>
 *
 * <p>覆盖的口径：</p>
 * <ul>
 *   <li>多目录串行解析：两个目录的记录全部产出，目录列表保持配置顺序；</li>
 *   <li>目录级异常隔离：装配期抛错或流中报错的目录只被跳过并告警，
 *       其余目录照常解析，且失败目录已产出的记录不丢失；</li>
 *   <li>分隔符与去重：中英文逗号/分号均可切分，重复目录只解析一次；</li>
 *   <li>空白配置回退当前用户主目录；</li>
 *   <li>{@code ~} 与 {@code ~账号} 简写展开。</li>
 * </ul>
 */
public final class BaseUsageParserMultiHomeExample {

    /**
     * 夹具记录的统一发起时刻（epoch 毫秒）
     */
    private static final long FIXTURE_START_TIME = 1700000000000L;

    /**
     * 断言失败计数
     */
    private static int failures;

    private BaseUsageParserMultiHomeExample() {
    }

    /**
     * 示例入口：合成夹具、跑解析、逐项断言，失败则抛出异常。
     *
     * @param args 未使用
     * @throws Exception 临时目录读写失败
     */
    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("usage-multihome-fixture");
        try {
            run(base);
        } finally {
            deleteQuietly(base);
        }
        if (failures > 0) {
            throw new IllegalStateException("多目录解析夹具存在 " + failures + " 处不符合预期");
        }
        System.out.println("USAGE-MULTIHOME-FIXTURE=PASS");
    }

    /**
     * 在临时根目录下布置夹具并执行断言。
     *
     * @param base 临时根目录
     * @throws IOException 夹具写入失败
     */
    private static void run(Path base) throws IOException {
        Path homeA = Files.createDirectories(base.resolve("alice"));
        Path homeB = Files.createDirectories(base.resolve("bob"));
        Path homeBad = Files.createDirectories(base.resolve("bad"));
        write(homeA.resolve("usage.txt"), "a1,claude,100,50", "a2,claude,200,60");
        write(homeB.resolve("usage.txt"), "b1,gpt,300,70");
        write(homeBad.resolve("boom"), "x");

        Path absA = homeA.toAbsolutePath().normalize();
        Path absB = homeB.toAbsolutePath().normalize();

        // 1) 多目录串行解析：两个目录的记录全部产出
        StubParser multi = new StubParser(homeA + "," + homeB);
        List<AiUsage> all = multi.streamAll().collectList().block();
        check("多目录记录全部产出=3", all != null && all.size() == 3);
        check("目录列表按配置顺序且绝对化",
                multi.homesView().equals(List.of(absA, absB)));

        // 2) 目录级异常隔离：装配期抛错的目录被跳过，其余目录照常
        StubParser isolated = new StubParser(homeA + "," + homeBad + "," + homeB);
        List<AiUsage> partial = isolated.streamAll().collectList().block();
        check("坏目录被隔离，其余目录照常=3", partial != null && partial.size() == 3);

        // 3) 流中失败：已产出记录不丢，后续目录继续
        MidStreamFailParser mid = new MidStreamFailParser(homeBad + "," + homeB);
        List<AiUsage> recovered = mid.streamAll().collectList().block();
        check("流中失败不丢弃已产出记录，后续目录继续=2",
                recovered != null && recovered.size() == 2
                        && "bad-partial".equals(recovered.get(0).getRequestId())
                        && "b1".equals(recovered.get(1).getRequestId()));

        // 4) 中英文逗号/分号切分 + 重复目录去重
        StubParser sep = new StubParser(homeA + "；" + homeA + ";" + homeB + "，" + homeB);
        List<AiUsage> dedup = sep.streamAll().collectList().block();
        check("多分隔符切分且重复目录只解析一次=3", dedup != null && dedup.size() == 3);
        check("重复目录去重后=2", sep.homesView().size() == 2);

        // 5) 空白配置回退当前用户主目录
        Path baseHome = Paths.get(System.getProperty("user.home", "."));
        StubParser defaults = new StubParser("   ");
        check("空白配置回退 user.home", defaults.homesView().equals(List.of(baseHome)));

        // 6) ~ 与 ~账号 简写
        StubParser tilde = new StubParser("~");
        check("~ 展开为 user.home", tilde.homesView().equals(List.of(baseHome)));
        Path parent = baseHome.getParent();
        if (parent == null) {
            check("~账号 展开跳过（user.home 无父目录）", true);
        } else {
            Path sibling = parent.resolve("someone-else");
            StubParser tildeName = new StubParser("~someone-else");
            check("~账号 展开为主目录同级目录",
                    tildeName.homesView().equals(List.of(sibling)));
        }
    }

    /**
     * 构造一条夹具用量记录。
     *
     * @param id     请求号
     * @param model  模型名
     * @param input  输入 Token
     * @param output 输出 Token
     * @return 用量记录
     */
    private static AiUsage usage(String id, String model, int input, int output) {
        return AiUsage.builder()
                .provider("stub")
                .model(model)
                .requestId(id)
                .inputTokens(input)
                .outputTokens(output)
                .totalTokens(input + output)
                .startTime(FIXTURE_START_TIME)
                .build();
    }

    /**
     * 读取 {@code usage.txt}（每行 id,model,input,output）；目录不存在该文件时返回空。
     *
     * @param home 解析根目录
     * @return 用量记录列表
     */
    private static List<AiUsage> readUsage(Path home) {
        Path file = home.resolve("usage.txt");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<AiUsage> result = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] cols = line.split(",");
                result.add(usage(cols[0], cols[1],
                        Integer.parseInt(cols[2]), Integer.parseInt(cols[3])));
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 从 {@code usage.txt} 读记录的夹具解析器；目录含 {@code boom} 标记文件时装配期抛错。
     */
    private static class StubParser extends BaseUsageParser {

        private StubParser(String homes) {
            super(homes);
        }

        @Override
        public String name() {
            return "stub";
        }

        /**
         * 暴露解析目录列表供断言。
         *
         * @return 解析目录列表
         */
        List<Path> homesView() {
            return userHomes();
        }

        @Override
        protected List<AiUsage> parseHome(Path home) {
            if (Files.isRegularFile(home.resolve("boom"))) {
                throw new IllegalStateException("模拟目录级解析失败: " + home);
            }
            return readUsage(home);
        }
    }

    /**
     * 坏目录先产出一条记录再报错的夹具解析器，用于验证流中异常不丢弃已产出记录。
     */
    private static final class MidStreamFailParser extends StubParser {

        private MidStreamFailParser(String homes) {
            super(homes);
        }

        @Override
        protected Flux<AiUsage> streamHome(Path home) {
            if (Files.isRegularFile(home.resolve("boom"))) {
                return Flux.just(usage("bad-partial", "claude", 10, 5))
                        .concatWith(Flux.error(new IllegalStateException("模拟流中失败")));
            }
            return super.streamHome(home);
        }
    }

    /**
     * 写入夹具文件。
     *
     * @param path  目标文件
     * @param lines 行内容
     * @throws IOException 写入失败
     */
    private static void write(Path path, String... lines) throws IOException {
        Files.write(path, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 记录一条断言结果，失败时累计次数。
     *
     * @param name 断言描述
     * @param ok   是否通过
     */
    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  PASS " : "  FAIL ") + name);
    }

    /**
     * 递归删除夹具目录，失败不影响结论。
     *
     * @param base 临时根目录
     */
    private static void deleteQuietly(Path base) {
        try (Stream<Path> walk = Files.walk(base)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响断言结论
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
