package harness;

import com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiOrder;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.common.support.spi.resolver.OsgiServiceResolver;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.datasource.support.engine.CachingEngine;
import com.chua.datasource.support.annotation.TableName;
import com.chua.sqlite.support.engine.SqliteEngine;
import com.chua.osgi.support.FelixOsgiLauncher;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceRegistration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Felix bundles exercise SPI enumeration and a single host engine across revisions. */
public final class EngineSpiLifecycle {
    private static int total;
    private static int failed;

    /** Source tag isolates cleanup to the definition owned by this probe. */
    public static final class ProbeResolver extends OsgiServiceResolver { }

    @SpiOrder(77)
    public static final class Task implements Runnable {
        private final AtomicInteger calls;
        public Task(AtomicInteger calls) { this.calls = calls; }
        @Override public void run() { calls.incrementAndGet(); }
    }

    @TableName("lifecycle")
    public static final class Row {
        private int id;
        private String value;
        public Row() { }
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    @Spi(value = "engine-interceptor", order = 20)
    public static final class StaticInterceptor implements EngineInterceptor {
        private static final AtomicInteger CONSTRUCTED = new AtomicInteger();
        public StaticInterceptor(AbstractEngine engine) { CONSTRUCTED.incrementAndGet(); }
    }

    private static void check(String name, boolean success) {
        total++;
        if (!success) { failed++; }
        System.out.println((success ? "PASS " : "FAIL ") + name);
    }

    private static EngineInterceptor plugin(List<EngineInterceptor> current) {
        return current.stream().filter(it -> it.getClass().getName().equals("fixture.Plugin"))
                .findFirst().orElse(null);
    }

    private static void registryChecks(FelixOsgiLauncher launcher) {
        ServiceProvider<Runnable> provider = ServiceProvider.of(Runnable.class);
        check("miss before registration is empty", provider.getDefinitions("TASK").isEmpty());
        Task first = new Task(new AtomicInteger());
        Task second = new Task(new AtomicInteger());
        ServiceRegistration<Runnable> one = launcher.getFrameworkBundleContext().registerService(Runnable.class, first, null);
        ServiceRegistration<Runnable> two = launcher.getFrameworkBundleContext().registerService(Runnable.class, second, null);
        ServiceDefinition staticDefinition = new ServiceDefinition();
        staticDefinition.setName("TASK");
        staticDefinition.setType(Runnable.class);
        staticDefinition.setImplClass(Task.class);
        staticDefinition.setFinderType(ProbeResolver.class);
        try {
            List<Runnable> values = provider.getNewExtensions(null);
            check("same class registrations remain distinct", values.stream().anyMatch(it -> it == first)
                    && values.stream().anyMatch(it -> it == second));
            check("extension names include live service", provider.getExtensions().contains("TASK"));
            check("type map includes live service", provider.listType().get("TASK") == (Class<?>) Task.class);
            check("shared map uses live service", values.contains(provider.list().get("TASK")));
            check("constructed map uses live service", values.contains(provider.list(new Object[0]).get("TASK")));
            check("collection includes live service", !provider.collect().isEmpty()
                    && provider.collect().stream().allMatch(it -> it == first || it == second));
            check("parameter collection includes live service", !provider.collect(new Object[0]).isEmpty()
                    && provider.collect(new Object[0]).stream().allMatch(it -> it == first || it == second));
            check("optional lookup uses live service", values.contains(provider.getIfPresent("TASK").orElse(null)));
            check("unnamed optional lookup uses live service", values.contains(provider.getIfPresent(null).orElse(null)));
            check("dynamic definitions preserve SPI order", provider.getDefinitions("TASK").stream()
                    .allMatch(it -> it.getOrder() == 77));
            provider.register(staticDefinition);
            check("unconstructed static definition does not hide dynamic instances", provider.getDefinitions(null).stream()
                    .filter(it -> it.getObj() == first || it.getObj() == second).count() == 2);
            provider.unregister("TASK", ProbeResolver.class);
            Runnable unnamed = provider.getNewExtension((String) null);
            check("null-name new lookup uses a registered instance", unnamed == first || unnamed == second);
            Runnable existing = provider.getExtension((String) null);
            check("null-name shared lookup uses a registered instance", existing == first || existing == second);
            Runnable deep = provider.getDeepNewExtension("task");
            check("deep lookup uses a registered instance", deep == first || deep == second);
            Runnable typed = provider.getNewExtension(Task.class);
            check("typed lookup uses a registered instance", typed == first || typed == second);
            one.unregister();
            one = null;
            check("unregister removes only one same-class registration", provider.getNewExtension((String) null) == second);
            two.unregister();
            two = null;
            check("unregistered services disappear from enumeration", provider.getDefinitions("TASK").isEmpty());
            check("unregistered services disappear from maps", provider.list().isEmpty()
                    && provider.listType().isEmpty() && provider.getExtensions().isEmpty());
            check("unregistered services disappear from optional lookup", provider.getIfPresent("TASK").isEmpty());
            check("null-name lookup does not retain removed service", provider.getNewExtension((String) null) == null);
        } finally {
            provider.unregister("TASK", ProbeResolver.class);
            if (one != null) { one.unregister(); }
            if (two != null) { two.unregister(); }
        }
    }

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        FelixOsgiLauncher launcher = new FelixOsgiLauncher();
        launcher.start(Map.of("org.osgi.framework.storage", Files.createTempDirectory(work, "engine-felix-").toString(),
                "org.osgi.framework.system.packages.extra",
                "com.chua.common.support.lang.datasource.engine.interceptor;version=1.0.0"));
        Path database = Files.createTempFile(work, "engine-spi-", ".sqlite");
        SqliteEngine engine = new SqliteEngine();
        engine.addDataSource("lifecycle", database.toString());
        Bundle bundle = null;
        ServiceProvider<EngineInterceptor> provider = ServiceProvider.of(EngineInterceptor.class);
        ServiceDefinition definition = new ServiceDefinition();
        definition.setName("ENGINE-INTERCEPTOR");
        definition.setType(EngineInterceptor.class);
        definition.setImplClass(StaticInterceptor.class);
        definition.setFinderType(ProbeResolver.class);
        definition.setOrder(20);
        provider.register(definition);
        try {
            registryChecks(launcher);
            List<EngineInterceptor> initial = engine.interceptors();
            int initialConstructions = StaticInterceptor.CONSTRUCTED.get();
            check("static extension is constructed", initialConstructions == 1);
            check("unchanged registry reuses snapshot", engine.interceptors() == initial);
            EngineInterceptor staticInstance = initial.stream().filter(it -> it instanceof StaticInterceptor).findFirst().orElseThrow();
            engine.executeSql("CREATE TABLE lifecycle (id INTEGER PRIMARY KEY, value TEXT)");
            engine.executeSql("INSERT INTO lifecycle VALUES (1, 'initial')");
            check("dynamic named miss before install", provider.getDefinitions("PLUGIN").isEmpty());
            bundle = launcher.getFrameworkBundleContext().installBundle(Path.of(args[1]).toUri().toString());
            check("bundle install has not run activator", plugin(engine.interceptors()) == null);
            bundle.start();
            EngineInterceptor first = plugin(engine.interceptors());
            check("started bundle visible on existing engine", first != null);
            check("batch enumeration includes dynamic instance", provider.getNewExtensions(null, engine)
                    .stream().anyMatch(it -> it == first));
            check("named new extension uses registered object", provider.getNewExtension("plugin", engine) == first);
            engine.querySql("SELECT * FROM lifecycle");
            check("query really invokes v1 before and after", "v1-before,v1-after".equals(
                    System.getProperty("engine.spi.trace")));
            check("one static key does not bypass dynamic resolver", provider.getDefinitions(null).stream()
                    .anyMatch(it -> it.getObj() == first));
            check("static instance retained while bundle is added", engine.interceptors().contains(staticInstance));

            System.clearProperty("engine.spi.trace");
            try (var input = Files.newInputStream(Path.of(args[2]))) { bundle.update(input); }
            EngineInterceptor second = plugin(engine.interceptors());
            check("bundle update replaces instance", second != null && second != first);
            check("bundle revision has a new class loader", second != null && first != null
                    && second.getClass().getClassLoader() != first.getClass().getClassLoader());
            engine.executeSql("UPDATE lifecycle SET value='updated' WHERE id=1");
            check("update invokes only v2", "v2-before-update,v2-after-update".equals(System.getProperty("engine.spi.trace")));
            check("enumeration releases old revision", provider.getNewExtensions(null, engine).stream().noneMatch(it -> it == first));
            check("unchanged dynamic service reuses snapshot", engine.interceptors() == engine.interceptors());
            int beforeHotReads = StaticInterceptor.CONSTRUCTED.get();
            for (int index = 0; index < 100; index++) { engine.interceptors(); }
            check("hot registry reads do not reconstruct static extensions", beforeHotReads == StaticInterceptor.CONSTRUCTED.get());

            System.clearProperty("engine.spi.trace");
            Bundle active = bundle;
            engine.interceptQuery("SELECT stop during action", new Object[0], () -> {
                List<Map<String, Object>> rows = engine.querySql("SELECT * FROM lifecycle");
                try { active.stop(); } catch (Exception failure) { throw new IllegalStateException(failure); }
                return rows;
            });
            check("one operation keeps paired callbacks across stop", "v2-before,v2-before,v2-after,v2-after".equals(
                    System.getProperty("engine.spi.trace")));
            check("later operation has no stopped service", plugin(engine.interceptors()) == null);
            System.clearProperty("engine.spi.trace");
            engine.querySql("SELECT * FROM lifecycle");
            check("no callback reaches stopped service on next operation", System.getProperty("engine.spi.trace") == null);
            bundle.start();
            EngineInterceptor restarted = plugin(engine.interceptors());
            check("bundle restart returns fresh registered instance", restarted != null && restarted != second);
            CachingEngine cached = CachingEngine.create(engine).addCacheTable("lifecycle");
            check("real cached page returns committed value", "updated".equals(cached.query(Row.class).page(1, 10).records().get(0).getValue()));
            System.clearProperty("engine.spi.trace");
            System.setProperty("engine.spi.stopBefore", "true");
            cached.query(Row.class).page(1, 10);
            System.clearProperty("engine.spi.stopBefore");
            check("cached page retains callback pair when bundle stops in before", "v2-before,v2-after".equals(System.getProperty("engine.spi.trace")));
            System.clearProperty("engine.spi.trace");
            cached.query(Row.class).page(1, 10);
            check("next cached page omits stopped plugin", System.getProperty("engine.spi.trace") == null);
            bundle.uninstall();
            bundle = null;
            check("uninstall removes dynamic definition", plugin(engine.interceptors()) == null);
            check("uninstall removes named extension", provider.getDefinitions("plugin").isEmpty());
            check("static instance retained throughout lifecycle", engine.interceptors().contains(staticInstance));
            engine.close();
            check("closed engine drops interceptor snapshot", engine.interceptors().isEmpty());
        } finally {
            if (bundle != null && bundle.getState() != Bundle.UNINSTALLED) { bundle.uninstall(); }
            engine.close();
            provider.unregister("ENGINE-INTERCEPTOR", ProbeResolver.class);
            launcher.stop();
            System.clearProperty("engine.spi.trace");
            System.clearProperty("engine.spi.stopBefore");
            Files.deleteIfExists(database);
            Files.deleteIfExists(Path.of(database + "-wal"));
            Files.deleteIfExists(Path.of(database + "-shm"));
        }
        System.out.println("RESULT engineSpiTotal=" + total + " fail=" + failed);
        if (failed != 0) { throw new AssertionError("engine SPI failures=" + failed); }
    }
}
