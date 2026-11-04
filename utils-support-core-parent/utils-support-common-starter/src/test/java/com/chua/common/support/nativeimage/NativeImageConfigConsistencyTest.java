package com.chua.common.support.nativeimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * native-image 元数据一致性测试。
 *
 * <p>校验 {@code reflect-config.json} 与 {@code proxy-config.json} 中登记的类型在源码中真实存在，
 * 避免包名或类名写错后，原生镜像构建时只输出一条容易被忽略的告警。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
class NativeImageConfigConsistencyTest {

    /**
     * 元数据目录
     */
    private static final String BASE_DIR =
            "META-INF/native-image/com.chua/utils-support-common-starter/";

    /**
     * 反射配置中登记的类型必须可加载。
     *
     * @throws Exception 读取或解析失败
     */
    @Test
    void reflectConfigClassesExist() throws Exception {
        List<String> names = readNames(BASE_DIR + "reflect-config.json");
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            if (!isLoadable(name)) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(), "reflect-config.json 中存在无法加载的类型: " + missing);
    }

    /**
     * 动态代理配置中登记的接口必须可加载。
     *
     * @throws Exception 读取或解析失败
     */
    @Test
    void proxyConfigInterfacesExist() throws Exception {
        List<String> names = readProxyInterfaces();
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            if (!isLoadable(name)) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(), "proxy-config.json 中存在无法加载的接口: " + missing);
    }

    /**
     * 读取 reflect-config.json 中的全部类型名。
     *
     * @param path 资源路径
     * @return 类型名列表
     * @throws Exception 读取或解析失败
     */
    private List<String> readNames(String path) throws Exception {
        List<String> names = new ArrayList<>();
        try (InputStream stream = getResource(path)) {
            JsonNode root = new ObjectMapper().readTree(stream);
            for (JsonNode node : root) {
                if (node.hasNonNull("name")) {
                    names.add(node.get("name").asText());
                }
            }
        }
        return names;
    }

    /**
     * 读取 proxy-config.json 中的接口名列表。
     *
     * <p>proxy-config.json 是「接口列表的列表」，每个元素是一组需要生成动态代理的接口。</p>
     *
     * @return 接口名列表
     * @throws Exception 读取或解析失败
     */
    private List<String> readProxyInterfaces() throws Exception {
        List<String> names = new ArrayList<>();
        try (InputStream stream = getResource(BASE_DIR + "proxy-config.json")) {
            JsonNode root = new ObjectMapper().readTree(stream);
            for (JsonNode node : root) {
                if (node.isArray()) {
                    for (JsonNode inner : node) {
                        names.add(inner.asText());
                    }
                } else {
                    names.add(node.asText());
                }
            }
        }
        return names;
    }

    /**
     * 打开类路径资源。
     *
     * @param path 资源路径
     * @return 输入流
     * @throws IllegalStateException 资源不存在
     */
    private InputStream getResource(String path) {
        InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (stream == null) {
            throw new IllegalStateException("类路径中不存在资源: " + path);
        }
        return stream;
    }

    /**
     * 判断类型能否被加载。
     *
     * @param name 全限定类名
     * @return 可加载返回 {@code true}
     */
    private boolean isLoadable(String name) {
        try {
            Class.forName(name, false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
