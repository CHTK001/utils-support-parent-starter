package com.chua.filesearch.support.nativeaccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * 可选依赖 {@code utils-support-native-filesearch} 的反射访问层。
 *
 * <p>本类不硬依赖 native 构件：构件存在时委托其 {@code RustFileSearchBridge}，
 * 缺失时只在首次调用处打印一次警告，由调用方降级到 JDK 遍历。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class RustFileSearchBridge {

    private static final Logger LOG = LoggerFactory.getLogger(RustFileSearchBridge.class);
    private static final String TARGET = "com.chua.filesearch.support.bridge.RustFileSearchBridge";
    private static final String DEPENDENCY = "com.chua:utils-support-native-filesearch:4.0.0.43";

    private static volatile boolean resolved;
    private static volatile Class<?> type;
    private static volatile Method[] fieldAccessors;

    private RustFileSearchBridge() {
    }

    /**
     * 原生搜索结果记录。
     *
     * @param path           文件路径
     * @param size           文件大小
     * @param lastModified   最后修改时间
     * @param isDirectory    是否目录
     * @param extension      扩展名
     * @param attributes     文件属性位
     * @param usnRecordId    USN 记录号
     * @param parentFileId   父目录文件号
     * @param allocatedSize  分配大小
     */
    public record FileResultData(String path, long size, long lastModified, boolean isDirectory,
                                 String extension, int attributes, long usnRecordId,
                                 long parentFileId, long allocatedSize) {
    }

    public static void loadLibrary() {
        call(method("loadLibrary"));
    }

    public static boolean isLoaded() {
        return Boolean.TRUE.equals(call(method("isLoaded")));
    }

    public static Throwable getLoadError() {
        Method method = method("getVersion");
        if (method == null) {
            return new IllegalStateException("请自行添加依赖 " + DEPENDENCY);
        }
        try {
            method.invoke(null);
            return null;
        } catch (InvocationTargetException e) {
            return e.getTargetException();
        } catch (Throwable e) {
            return e;
        }
    }

    public static String getVersion() {
        Object value = call(method("getVersion"));
        return value instanceof String version ? version : null;
    }

    public static void cancel() {
        call(method("cancel"));
    }

    public static int searchByName(String root, String pattern, int max,
                                   Consumer<FileResultData> callback) {
        return search("searchByName", new Class<?>[]{String.class, String.class, int.class, Consumer.class},
                root, pattern, max, callback);
    }

    public static int getTree(String root, int depth, int max, Consumer<FileResultData> callback) {
        return search("getTree", new Class<?>[]{String.class, int.class, int.class, Consumer.class},
                root, depth, max, callback);
    }

    public static int searchBySize(String root, long minSize, long maxSize, int max,
                                   Consumer<FileResultData> callback) {
        return search("searchBySize",
                new Class<?>[]{String.class, long.class, long.class, int.class, Consumer.class},
                root, minSize, maxSize, max, callback);
    }

    public static int searchByPath(String root, String pattern, int max,
                                   Consumer<FileResultData> callback) {
        return search("searchByPath", new Class<?>[]{String.class, String.class, int.class, Consumer.class},
                root, pattern, max, callback);
    }

    private static int search(String name, Class<?>[] parameterTypes, Object... args) {
        Method method = method(name, parameterTypes);
        if (method == null) {
            return -1;
        }
        Object[] actual = args.clone();
        actual[actual.length - 1] = toNativeConsumer((Consumer<?>) actual[actual.length - 1]);
        Object value = call(method, actual);
        return value instanceof Integer code ? code : -1;
    }

    private static Consumer<Object> toNativeConsumer(Consumer<?> callback) {
        if (callback == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Consumer<FileResultData> consumer = (Consumer<FileResultData>) callback;
        return data -> consumer.accept(convert(data));
    }

    private static FileResultData convert(Object nativeData) {
        Method[] accessors = fieldAccessors;
        if (accessors == null) {
            accessors = new Method[9];
            String[] names = {"path", "size", "lastModified", "isDirectory", "extension",
                    "attributes", "usnRecordId", "parentFileId", "allocatedSize"};
            for (int i = 0; i < names.length; i++) {
                try {
                    accessors[i] = nativeData.getClass().getMethod(names[i]);
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException("native 搜索结果缺少字段: " + names[i], e);
                }
            }
            fieldAccessors = accessors;
        }
        try {
            return new FileResultData((String) accessors[0].invoke(nativeData),
                    ((Number) accessors[1].invoke(nativeData)).longValue(),
                    ((Number) accessors[2].invoke(nativeData)).longValue(),
                    Boolean.TRUE.equals(accessors[3].invoke(nativeData)),
                    (String) accessors[4].invoke(nativeData),
                    ((Number) accessors[5].invoke(nativeData)).intValue(),
                    ((Number) accessors[6].invoke(nativeData)).longValue(),
                    ((Number) accessors[7].invoke(nativeData)).longValue(),
                    ((Number) accessors[8].invoke(nativeData)).longValue());
        } catch (InvocationTargetException | IllegalAccessException e) {
            throw new IllegalStateException("native 搜索结果转换失败", e);
        }
    }

    private static Object call(Method method, Object... args) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("文件搜索原生调用失败: " + e.getTargetException(),
                    e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("文件搜索原生调用失败: " + e, e);
        }
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        Class<?> target = type();
        if (target == null) {
            return null;
        }
        try {
            return target.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            LOG.warn("native 模块 {} 缺少方法 {}，请确认依赖 {} 的版本", TARGET, name, DEPENDENCY);
            return null;
        }
    }

    private static Class<?> type() {
        if (!resolved) {
            synchronized (RustFileSearchBridge.class) {
                if (!resolved) {
                    try {
                        type = Class.forName(TARGET, true, RustFileSearchBridge.class.getClassLoader());
                    } catch (Throwable e) {
                        LOG.warn("未引入 native 模块 {}，原生文件搜索不可用；请自行添加依赖 {}",
                                TARGET, DEPENDENCY);
                    }
                    resolved = true;
                }
            }
        }
        return type;
    }
}
