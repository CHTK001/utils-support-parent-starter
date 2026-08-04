package com.chua.common.support.network.ipc.parser;

import com.chua.common.support.network.ipc.annotations.IpcMethod;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NullUnmarked;

/**
 * IPC 地址解析器，扫描对象上标注了 {@code @IpcMethod} 的方法，生成 path -> Method 映射。
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public class IpcAddressParser {

    /**
     * 解析给定对象上的 {@code @IpcMethod} 注解。
     *
     * @param bean 包含被注解方法的对象
     * @return 方法路径到方法对象的映射
     */
    public Map<String, Method> parse(Object bean) {
        Objects.requireNonNull(bean, "Bean cannot be null");
        Class<?> clazz = bean.getClass();
        Map<String, Method> map = new HashMap<>();

        for (Method method : clazz.getDeclaredMethods()) {
            IpcMethod annotation = method.getAnnotation(IpcMethod.class);
            if (annotation != null) {
                String path = annotation.value();
                if (path == null || path.isEmpty()) {
                    // 方法名作为路径（去掉前导斜杠以保持一致）
                    path = method.getName();
                }
                // 标准化路径：确保以斜杠开头且不以斜杠结尾（除非是根路径）
                if (!path.startsWith("/")) {
                    path = "/" + path;
                }
                // 移除尾部斜杠
                if (path.length() > 1 && path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                method.setAccessible(true);
                map.put(path, method);
            }
        }
        return map;
    }
}