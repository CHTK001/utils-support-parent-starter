import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.DiscoveryFilter;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.OutputDirectoryCreator;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.reporting.ReportEntry;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 最小 JUnit 6.0.3 运行器：绕过 surefire（离线，打不到 junit-platform-launcher），
 * 直接在 JVM 内调用 JupiterTestEngine 跑 JUnit 5/6 测试。
 *
 * @author CH
 */
public class JUnitRunner {

    static int pass = 0;
    static int fail = 0;
    static int skip = 0;
    static int total = 0;

    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        JupiterTestEngine engine = new JupiterTestEngine();
        List<DiscoverySelector> selectors = Collections.singletonList(DiscoverySelectors.selectClass(args[0]));
        for (int i = 1; i < args.length; i++) {
            selectors.add(DiscoverySelectors.selectClass(args[i]));
        }

        EngineDiscoveryRequest request = new EngineDiscoveryRequest() {
            @Override
            public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> type) {
                if (type == ClassSelector.class) {
                    @SuppressWarnings("unchecked")
                    List<T> result = (List<T>) selectors;
                    return result;
                }
                return Collections.emptyList();
            }

            @Override
            public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> type) {
                return Collections.emptyList();
            }

            @Override
            public ConfigurationParameters getConfigurationParameters() {
                return new ConfigurationParameters() {
                    @Override
                    public Optional<String> get(String key) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<Boolean> getBoolean(String key) {
                        return Optional.empty();
                    }

                    @Override
                    public Set<String> keySet() {
                        return Collections.emptySet();
                    }
                };
            }

            @Override
            public OutputDirectoryCreator getOutputDirectoryCreator() {
                // 不需要甲方 launcher 提供：把输出写向临时目录即可
                return new OutputDirectoryCreator() {
                    @Override
                    public Path getRootDirectory() {
                        return Paths.get(System.getProperty("user.dir"), "target/junit");
                    }

                    @Override
                    public Path createOutputDirectory(TestDescriptor testDescriptor) {
                        return getRootDirectory();
                    }
                };
            }
        };

        TestDescriptor root = engine.discover(request, UniqueId.forEngine("junit-jupiter-6.0.3"));
        EngineExecutionListener listener = new EngineExecutionListener() {
            @Override
            public void dynamicTestRegistered(TestDescriptor testDescriptor) {
                total++;
            }

            @Override
            public void executionSkipped(TestDescriptor testDescriptor, String reason) {
                skip++;
            }

            @Override
            public void executionStarted(TestDescriptor testDescriptor) {
                System.out.println("  [start] " + testDescriptor.getUniqueId());
            }

            @Override
            public void executionFinished(TestDescriptor testDescriptor, TestExecutionResult result) {
                if (result.getStatus() == TestExecutionResult.Status.SUCCESSFUL) {
                    pass++;
                } else if (result.getStatus() == TestExecutionResult.Status.FAILED) {
                    fail++;
                    System.out.println("  [fail] " + testDescriptor.getUniqueId() + " -> " + result.getThrowable());
                } else {
                    skip++;
                    System.out.println("  [skip] " + testDescriptor.getUniqueId() + " -> " + result.getStatus());
                }
            }

            @Override
            public void reportingEntryPublished(TestDescriptor testDescriptor, ReportEntry entry) {
                // JUnit 6 ReportEntry 是类：getKeyValuePairs()
                System.out.println("  [report] " + entry.getKeyValuePairs());
            }
        };

        ExecutionRequest executionRequest = ExecutionRequest.create(root, listener,
                request.getConfigurationParameters());
        engine.execute(executionRequest);

        System.out.println("Pass=" + pass + " Fail=" + fail + " Skip=" + skip + " DynTests=" + total
                + " elapsed=" + (System.nanoTime() - start) / 1_000_000L + "ms");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
