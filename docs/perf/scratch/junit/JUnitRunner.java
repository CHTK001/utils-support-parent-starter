import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.DiscoveryFilter;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.reporting.ReportEntry;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最小 JUnit 6.0.3 运行器：绕过 surefire（离线，不下载 junit-platform-launcher），
 * 直接在 JVM 内调用 JupiterTestEngine 跑 JUnit 测试。
 *
 * @author CH
 */
public class JUnitRunner {

    public static void main(String[] args) throws Exception {
        int pass = 0;
        int fail = 0;
        int skip = 0;
        long start = System.nanoTime();

        JupiterTestEngine engine = new JupiterTestEngine();
        List<DiscoverySelector> selectors = java.util.Arrays.asList(
                DiscoverySelectors.selectClass(args[0])
        );
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
        };

        TestDescriptor root = engine.discover(request, UniqueId.forEngine("junit-jupiter-6.0.3"));
        ExecutionRequest executionRequest = ExecutionRequest.create(root, listener,
                request.getConfigurationParameters());
        engine.execute(executionRequest);

        System.out.println("Pass=" + pass + " Fail=" + fail + " Skip=" + skip + " elapsed="
                + (System.nanoTime() - start) / 1_000_000L + "ms");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
