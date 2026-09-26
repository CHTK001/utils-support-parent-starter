package com.chua.deeplearning.support.weka;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.task.classifier.ClassifierTask;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Weka 分类扩展点登记与端到端冒烟门（main 方法直跑，不依赖测试框架）。
 *
 * <p>校验 {@link ClassifierTask} 扩展点的登记链是否完整：注册文件存在、别名可见、
 * 无参构造可实例化、经扩展点取到的实例可完成「训练 -> 预测 -> 评估 -> 序列化」全流程。
 * 同时以直接实例化做对照，用于把缺陷定位在登记层而非算法层。</p>
 *
 * <p>运行方式（参数为待检查的 classes 目录，用于核对注册文件是否随包发布）：</p>
 * <pre>{@code
 * java -cp <类路径> com.chua.deeplearning.support.weka.WekaClassifierSpiSmokeTest <classes目录>
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class WekaClassifierSpiSmokeTest {

    /**
     * 别名：Weka 随机森林分类任务
     */
    private static final String ALIAS = "weka-random-forest";

    /**
     * 不存在的别名，用于校验未登记时的返回契约
     */
    private static final String UNKNOWN_ALIAS = "no-such-classifier-algo";

    /**
     * 实现类全限定名
     */
    private static final String IMPL = "com.chua.deeplearning.support.weka.rf.WekaRandomForestClassifier";

    /**
     * 结果行前缀标记
     */
    private static final String FAIL_MARKER = "RESULT:";

    /**
     * 样本行数（2 类各半，保证交叉验证每类均有样本）
     */
    private static final int SAMPLES = 40;

    private static int pass;

    private static int fail;

    private WekaClassifierSpiSmokeTest() {
    }

    /**
     * 执行门校验。
     *
     * @param args args[0] 为可选的 classes 目录，用于核对注册文件随包情况
     */
    public static void main(String[] args) {
        String classesDir = args.length > 0 ? args[0] : null;
        checkRegistrationFile(classesDir);
        checkAliasVisible();
        checkImplMapping();
        checkInstanceCreated();
        checkUnknownAliasReturnsNull();
        checkEndToEndViaSpi();
        checkEndToEndDirect();
        checkSerializationContract();
        System.out.println("结果: PASS=" + pass + ", FAIL=" + fail);
        System.out.println(FAIL_MARKER + (fail == 0 ? " PASS" : " FAIL"));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 校验注册文件是否随 classes 目录发布，并观察全部非空登记行。
     *
     * @param classesDir classes 目录，可为 空（跳过文件核对）
     */
    private static void checkRegistrationFile(String classesDir) {
        if (classesDir == null || classesDir.isBlank()) {
            observe("registrationFile 未提供 classes 目录，跳过文件核对");
            return;
        }
        Path file = Path.of(classesDir, "META-INF", "extensions", ClassifierTask.class.getName());
        boolean exists = Files.isRegularFile(file);
        int lines = 0;
        if (exists) {
            try {
                lines = (int) Files.readAllLines(file).stream()
                        .map(String::trim)
                        .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                        .count();
            } catch (Exception e) {
                observe("registrationFile 读取失败 " + e);
            }
        }
        observe("registrationFile exists=" + exists + " 有效登记行=" + lines);
        check("注册文件随包发布", exists);
        check("注册文件含有效登记行", lines > 0);
    }

    /**
     * 校验扩展点名集合是否含 Weka 别名（框架按大写存储，故忽略大小写比对）。
     */
    private static void checkAliasVisible() {
        ServiceProvider<ClassifierTask> provider = ServiceProvider.of(ClassifierTask.class);
        var names = provider.getExtensions();
        observe("extensions " + ClassifierTask.class.getSimpleName() + "=" + names);
        check("getExtensions 含 " + ALIAS, containsIgnoreCase(names, ALIAS));
        check("isSupport(" + ALIAS + ")", provider.isSupport(ALIAS));
    }

    /**
     * 校验别名到实现类的映射。
     */
    private static void checkImplMapping() {
        try {
            Map<String, Class<ClassifierTask>> types = ServiceProvider.of(ClassifierTask.class).listType();
            Class<ClassifierTask> type = getIgnoreCase(types, ALIAS);
            observe("listType " + ALIAS + "=" + (type == null ? "null" : type.getName()));
            check("别名可解析为 Class", type != null);
            check("实现类为 " + IMPL, type != null && IMPL.equals(type.getName()));
        } catch (Exception e) {
            check("别名可解析为 Class 异常: " + e, false);
        }
    }

    /**
     * 忽略大小写判断名称集合是否含目标别名。
     *
     * @param names 名称集合
     * @param alias 目标别名
     * @return 是否含该别名
     */
    private static boolean containsIgnoreCase(Iterable<String> names, String alias) {
        for (String name : names) {
            if (name != null && name.equalsIgnoreCase(alias)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 忽略大小写按键取值。
     *
     * @param values 键值映射
     * @param key    目标键
     * @return 命中的值，未命中返回 空
     */
    private static Class<ClassifierTask> getIgnoreCase(Map<String, Class<ClassifierTask>> values, String key) {
        for (var entry : values.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 校验扩展点可无参实例化。
     */
    private static void checkInstanceCreated() {
        ClassifierTask task = ServiceProvider.of(ClassifierTask.class).getExtension(ALIAS);
        observe("getExtension(" + ALIAS + ")=" + (task == null ? "null" : task.getClass().getName()));
        check("无参构造实例化成功", task != null);
    }

    /**
     * 校验未登记别名返回 空（不抛异常，供调用方判空）。
     */
    private static void checkUnknownAliasReturnsNull() {
        ClassifierTask task = ServiceProvider.of(ClassifierTask.class).getExtension(UNKNOWN_ALIAS);
        check("未登记别名返回 空", task == null);
    }

    /**
     * 经扩展点实例完成端到端流程。
     */
    private static void checkEndToEndViaSpi() {
        ClassifierTask task = ServiceProvider.of(ClassifierTask.class).getExtension(ALIAS);
        if (task == null) {
            check("端到端（经扩展点）跳过：实例为 空", false);
            return;
        }
        runEndToEnd("端到端（经扩展点）", task);
    }

    /**
     * 直接实例化完成端到端流程，作为算法层对照。
     */
    private static void checkEndToEndDirect() {
        try {
            Class<?> clazz = Class.forName(IMPL);
            runEndToEnd("端到端（直接实例化）", (ClassifierTask) clazz.getDeclaredConstructor().newInstance());
        } catch (Throwable t) {
            check("直接实例化对照可运行: " + t, false);
        }
    }

    /**
     * 执行「训练 -> 预测 -> 批量预测 -> 交叉验证评估」并核对结果。
     *
     * @param tag  场景标记
     * @param task 分类任务实例
     */
    private static void runEndToEnd(String tag, ClassifierTask task) {
        try {
            List<Map<String, Object>> samples = samples();
            ClassifierTask.Model model = task.train("risk", samples);
            check(tag + " 训练产出模型", model != null);
            ClassifierTask.Result result = model.predict(row(31, 9.0));
            observe(tag + " 预测 label=" + result.label() + " confidence=" + fmt(result.confidence()));
            check(tag + " 高值样本判为 high", "high".equals(result.label()));
            ClassifierTask.Result low = model.predict(row(1, 1.0));
            check(tag + " 低值样本判为 low", "low".equals(low.label()));
            List<ClassifierTask.Result> batch = model.predictBatch(List.of(row(30, 8.5), row(2, 2.5)));
            check(tag + " 批量预测保序", batch.size() == 2
                    && "high".equals(batch.get(0).label()) && "low".equals(batch.get(1).label()));
            ClassifierTask.Report report = model.evaluate(samples);
            observe(tag + " 评估 instances=" + report.numInstances() + " folds=" + report.numFolds()
                    + " accuracy=" + fmt(report.accuracyPct()) + " kappa=" + fmt(report.kappa()));
            check(tag + " 准确率高于随机线", report.accuracyPct() > 50.0);
        } catch (Throwable t) {
            check(tag + " 抛出异常: " + rootMessage(t), false);
        }
    }

    /**
     * 校验 模型 的序列化契约（接口声明 可序列化）。
     */
    private static void checkSerializationContract() {
        ClassifierTask task = ServiceProvider.of(ClassifierTask.class).getExtension(ALIAS);
        if (task == null) {
            check("序列化契约跳过：实例为 空", false);
            return;
        }
        try {
            ClassifierTask.Model model = task.train("risk", samples());
            byte[] bytes;
            try (var bos = new ByteArrayOutputStream(); var oos = new ObjectOutputStream(bos)) {
                oos.writeObject(model);
                oos.flush();
                bytes = bos.toByteArray();
            }
            ClassifierTask.Model restored;
            try (var ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                restored = (ClassifierTask.Model) ois.readObject();
            }
            var result = restored.predict(row(28, 7.5));
            observe("序列化往返 bytes=" + bytes.length + " 还原后预测 label=" + result.label());
            check("序列化往返后仍可预测", "high".equals(result.label()));
        } catch (Throwable t) {
            check("序列化往返 抛出异常: " + rootMessage(t), false);
        }
    }

    /**
     * 构造可分样本：risk 由 特征 决定，两类各半。
     *
     * @return 样本行列表
     */
    private static List<Map<String, Object>> samples() {
        var list = new ArrayList<Map<String, Object>>(SAMPLES);
        for (int i = 1; i <= SAMPLES; i++) {
            var row = row(i, (i % 5) + 1.0);
            row.put("risk", i > SAMPLES / 2 ? "high" : "low");
            list.add(row);
        }
        return list;
    }

    /**
     * 构造单行数据。
     *
     * @param age  年龄特征
     * @param score 分值特征
     * @return 行数据（列名 -> 值）
     */
    private static Map<String, Object> row(int age, double score) {
        var map = new LinkedHashMap<String, Object>();
        map.put("age", age);
        map.put("score", score);
        return map;
    }

    /**
     * 格式化数值，观察行使用。
     *
     * @param value 数值
     * @return 两位小数字符串
     */
    private static String fmt(double value) {
        return String.format("%.2f", value);
    }

    /**
     * 取根因消息。
     *
     * @param t 异常
     * @return 根因描述
     */
    private static String rootMessage(Throwable t) {
        var cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    /**
     * 输出观察值。
     *
     * @param text 观察内容
     */
    private static void observe(String text) {
        System.out.println("OBSERVE " + text);
    }

    /**
     * 记录单项校验结果。
     *
     * @param name 校验名
     * @param ok 是否通过
     */
    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  [通过] " + name);
        } else {
            fail++;
            System.out.println("  [失败] " + name);
        }
    }
}
