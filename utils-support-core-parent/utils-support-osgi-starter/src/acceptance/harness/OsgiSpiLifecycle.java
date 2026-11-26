package harness;

import com.chua.common.support.spi.ServiceDefinitionFinder;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.common.support.spi.resolver.OsgiServiceResolver;
import com.chua.osgi.support.FelixOsgiLauncher;
import org.osgi.framework.ServiceRegistration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the real Felix registry through the production dynamic SPI resolver. */
public final class OsgiSpiLifecycle {
    public static final class Task implements Runnable {
        private final AtomicInteger counter;
        Task(AtomicInteger counter) { this.counter = counter; }
        @Override public void run() { counter.incrementAndGet(); }
    }

    private static int failures;
    private static void check(String name, boolean condition) {
        System.out.println((condition ? "PASS " : "FAIL ") + name);
        if (!condition) { failures++; }
    }

    public static void main(String[] args) throws Exception {
        FelixOsgiLauncher launcher = new FelixOsgiLauncher();
        Path storage = Files.createTempDirectory(Path.of(args[0]), "felix-");
        launcher.start(Map.of("org.osgi.framework.storage", storage.toString()));
        ServiceRegistration<Runnable> registration = null;
        try {
            ServiceDefinitionFinder finder = new ServiceDefinitionFinder(new ConcurrentHashMap<>(), null);
            finder.setDynamicResolvers(List.of(new OsgiServiceResolver()), Runnable.class,
                    OsgiSpiLifecycle.class.getClassLoader());
            check("named miss before installation", !finder.getServiceDefinition("task").isPresent());
            AtomicInteger calls = new AtomicInteger();
            Task first = new Task(calls);
            registration = launcher.getFrameworkBundleContext().registerService(Runnable.class, first, null);
            ServiceDefinition installed = finder.getServiceDefinition("task");
            check("prior miss does not hide newly registered service", installed.getObj() == first);
            if (installed.getObj() instanceof Runnable service) { service.run(); }
            check("host really invokes plugin implementation", calls.get() == 1);
            check("type lookup sees current implementation", finder.getServiceDefinition(Task.class, new Object[0])
                    .getObj() == first);
            registration.unregister();
            registration = null;
            check("unregistered service is not retained by named lookup",
                    !finder.getServiceDefinition("task").isPresent());
            check("unregistered service is not retained by enumeration", finder.getDefinitions("task").isEmpty());
            Task second = new Task(calls);
            registration = launcher.getFrameworkBundleContext().registerService(Runnable.class, second, null);
            check("reinstall after another miss returns new instance",
                    finder.getServiceDefinition("task").getObj() == second);
            check("type lookup does not retain previous instance",
                    finder.getServiceDefinition(Task.class, new Object[0]).getObj() == second);
        } finally {
            if (registration != null) { registration.unregister(); }
            launcher.stop();
        }
        System.out.println("RESULT total=8 fail=" + failures);
        if (failures != 0) { throw new AssertionError("dynamic SPI failures=" + failures); }
    }
}
