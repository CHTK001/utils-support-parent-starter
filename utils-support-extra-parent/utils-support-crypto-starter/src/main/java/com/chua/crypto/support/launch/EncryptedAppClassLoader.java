package com.chua.crypto.support.launch;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 加密程序包类加载器
 *
 * <p>从加密后的 FatJar/Jar 中加载类与资源：
 * <ul>
 *   <li>类条目 — 惰性读取，命中 {@code CHKJ} 魔数时透明解密后 defineClass</li>
 *   <li>资源条目 — 经 {@link #findResource(String)}/{@link #findResources(String)}
 *       返回解密流 URL（自定义 URLStreamHandler，不污染全局工厂），
 *       因此配置文件等敏感资源在磁盘保持密文、在应用侧自动明文</li>
 *   <li>依赖包 — 由父加载器（已解密到临时目录的 lib jar）按标准双亲委派提供</li>
 * </ul>
 *
 * @author CH
 * @since 2026-08-26
 */
public class EncryptedAppClassLoader extends URLClassLoader {

    /**
     * FatJar 应用类根前缀（SpringBoot 结构）
     */
    private static final String BOOT_CLASSES_PREFIX = "BOOT-INF/classes/";

    static {
        registerAsParallelCapable();
    }

    /**
     * 加密程序包文件
     */
    private final JarFile jar;

    /**
     * 主密钥
     */
    private final byte[] master;

    /**
     * 解密资源 URL 处理器
     */
    private final Handler handler = new Handler();

    /**
     * 构造类加载器
     *
     * @param jarFile 加密后的可执行 jar
     * @param master  主密钥（32 字节）
     * @param parent  父加载器（依赖包加载器）
     * @throws IOException jar 打开失败
     */
    public EncryptedAppClassLoader(File jarFile, byte[] master, ClassLoader parent) throws IOException {
        super(new URL[0], parent);
        this.jar = new JarFile(jarFile);
        this.master = master.clone();
    }

    /**
     * 查找并定义类：支持根路径与 BOOT-INF/classes 双前缀，命中 CHKJ 密文自动解密
     */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/').concat(".class");
        for (String candidate : candidates(path)) {
            JarEntry entry = jar.getJarEntry(candidate);
            if (entry == null) {
                continue;
            }
            try {
                byte[] bytes = entryBytes(entry);
                defineOrReusePackage(name);
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException("类条目读取失败: " + name, e);
            }
        }
        throw new ClassNotFoundException(name);
    }

    /**
     * 查找资源：返回透明解密的流 URL
     */
    @Override
    public URL findResource(String name) {
        if (name == null || name.endsWith("/")) {
            return null;
        }
        for (String candidate : candidates(name)) {
            JarEntry entry = jar.getJarEntry(candidate);
            if (entry != null && !entry.isDirectory()) {
                return toChkUrl(candidate);
            }
        }
        return null;
    }

    /**
     * 枚举资源：当前实现返回单元素枚举（同一名称在包内唯一）
     */
    @Override
    public Enumeration<URL> findResources(String name) {
        URL url = findResource(name);
        return url != null ? Collections.enumeration(Collections.singletonList(url)) : Collections.emptyEnumeration();
    }

    /**
     * 获取包内原始/解密后的条目流（供 URL 连接复用）
     *
     * @param entryName 条目全名
     * @return 明文输入流；条目不存在返回 null
     */
    InputStream openDecryptedStream(String entryName) {
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) {
            return null;
        }
        try {
            return new ByteArrayInputStream(entryBytes(entry));
        } catch (IOException e) {
            throw new IllegalStateException("条目读取失败: " + entryName, e);
        }
    }

    /**
     * 读取条目字节并按需解密
     *
     * @param entry 条目
     * @return 明文字节
     * @throws IOException 读取失败
     */
    private byte[] entryBytes(JarEntry entry) throws IOException {
        byte[] raw = readAll(jar.getInputStream(entry));
        return PayloadCipher.isEncryptedEntry(raw) ? PayloadCipher.decryptEntry(master, raw) : raw;
    }

    /**
     * 定义包（若尚未定义），使清单属性与注解可见
     *
     * @param className 类全名
     */
    private void defineOrReusePackage(String className) {
        int dot = className.lastIndexOf('.');
        if (dot <= 0) {
            return;
        }
        String pkg = className.substring(0, dot);
        if (getDefinedPackage(pkg) == null) {
            definePackage(pkg, "1.0", null, null, null, null, null, null);
        }
    }

    /**
     * 候选条目路径列表：原样 → BOOT-INF/classes 前缀
     *
     * @param path 相对路径
     * @return 候选列表
     */
    private java.util.List<String> candidates(String path) {
        java.util.List<String> list = new java.util.ArrayList<>(2);
        list.add(path);
        list.add(BOOT_CLASSES_PREFIX + path);
        return list;
    }

    /**
     * 将包内条目包装为透明解密 URL
     *
     * @param entryName 条目名
     * @return chkres 协议 URL
     */
    private URL toChkUrl(String entryName) {
        try {
            String spec = "chkres://chua/" + java.net.URLEncoder.encode(entryName, StandardCharsets.UTF_8);
            return new URL(null, spec, handler);
        } catch (IOException e) {
            throw new IllegalStateException("资源 URL 构建失败: " + entryName, e);
        }
    }

    /**
     * 读取流的全部字节
     *
     * @param in 输入流
     * @return 字节
     * @throws IOException 读取失败
     */
    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream input = in) {
            return input.readAllBytes();
        }
    }

    /**
     * 解密资源协议处理器
     */
    private class Handler extends URLStreamHandler {

        /**
         * 打开解密连接
         *
         * @param u 资源 URL
         * @return 连接
         */
        @Override
        protected URLConnection openConnection(URL u) {
            return new ChkURLConnection(u);
        }
    }

    /**
     * 条目内容连接：getInputStream 返回解密字节流
     */
    private class ChkURLConnection extends URLConnection {

        /**
         * 构造连接
         *
         * @param url 资源 URL
         */
        protected ChkURLConnection(URL url) {
            super(url);
        }

        /**
         * 无需预连接动作
         */
        @Override
        public void connect() {
            // 惰性读取，无需实现
        }

        /**
         * 返回解密后的条目流
         *
         * @return 明文流
         */
        @Override
        public InputStream getInputStream() {
            String encoded = getURL().getPath();
            String name = URLDecoder.decode(encoded.substring(encoded.indexOf('/') + 1), StandardCharsets.UTF_8);
            InputStream stream = openDecryptedStream(name);
            if (stream == null) {
                throw new IllegalStateException("资源不存在: " + name);
            }
            return stream;
        }
    }

    /**
     * 关闭加载器并释放底层 JarFile 句柄
     *
     * @throws IOException 关闭失败
     */
    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            jar.close();
            java.util.Arrays.fill(master, (byte) 0);
        }
    }

    /**
     * 供诊断使用：列出包内全部条目名（只读快照）
     *
     * @return 条目名映射（名称→是否加密）
     */
    public Map<String, Boolean> snapshotEntries() {
        Map<String, Boolean> entries = new LinkedHashMap<>();
        Enumeration<JarEntry> all = jar.entries();
        while (all.hasMoreElements()) {
            JarEntry e = all.nextElement();
            boolean encrypted;
            try {
                encrypted = PayloadCipher.isEncryptedEntry(readAll(jar.getInputStream(e)));
            } catch (Exception ex) {
                encrypted = false;
            }
            entries.put(e.getName(), encrypted);
        }
        return entries;
    }
}
