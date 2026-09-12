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
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 加密程序包自定义类加载器（惰性模式）
 *
 * <p>从加密包内按需解密类与资源，密文不落盘：
 * <ul>
 *   <li>类条目 — 惰性读取，命中 {@code CHKJ} 魔数透明解密后 defineClass</li>
 *   <li>资源条目 — 经 {@link #findResource} 返回解密流 URL（私有协议处理器）</li>
 *   <li>主密钥以分片形态持有（{@link KeyShard}），堆中无完整密钥</li>
 * </ul>
 *
 * <p>注意：目录式资源枚举（Spring 组件扫描）在惰性模式下受限，
 * 默认启动采用"解密装载"模式；本加载器经 {@code -Dchua.crypto.lazy=true} 启用。
 *
 * @author CH
 * @since 2026-08-26
 */
public class EncryptedAppClassLoader extends URLClassLoader {

    /**
      * fatjar 应用类根前缀（springboot 结构）
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
     * 主密钥分片（堆中不存完整密钥）
     */
    private final byte[][] masterShards;

    /**
     * 解密资源 URL 处理器
     */
    private final Handler handler = new Handler();

    /**
     * 构造类加载器
     *
     * @param jarFile 加密后的可执行 jar
     * @param master  主密钥（32 字节，构造后即分片并清零入参）
     * @param parent  父加载器（依赖包加载器）
     * @throws IOException jar 打开失败
     */
    public EncryptedAppClassLoader(File jarFile, byte[] master, ClassLoader parent) throws IOException {
        super(new URL[0], parent);
        this.jar = new JarFile(jarFile);
        this.masterShards = KeyShard.shard(master);
    }

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

    @Override
    public Enumeration<URL> findResources(String name) {
        URL url = findResource(name);
        return url != null ? Collections.enumeration(Collections.singletonList(url))
                : Collections.emptyEnumeration();
    }

    /**
     * 获取包内原始/解密后的条目流（供 URL 连接复用）
     *
     * @param entryName 条目全名
     * @return 明文输入流；条目不存在返回 空
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
     * 读取条目字节并按需解密（密钥临时拼合、用后清零）
     *
     * @param entry 条目
     * @return 明文字节
     * @throws IOException 读取失败
     */
    private byte[] entryBytes(JarEntry entry) throws IOException {
        byte[] raw = readAll(jar.getInputStream(entry));
        if (!PayloadCipher.isEncryptedEntry(raw)) {
            return raw;
        }
        byte[] master = KeyShard.join(masterShards);
        try {
            return PayloadCipher.decryptEntry(master, raw);
        } finally {
            KeyShard.wipe(master);
        }
    }

    /**
     * 定义包（若尚未定义）
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
     * @author CH
     * @since 4.0.0
     */
    private class Handler extends URLStreamHandler {

        @Override
        protected URLConnection openConnection(URL u) {
            return new ChkURLConnection(u);
        }
    }

    /**
      * 条目内容连接：获取输入流 返回解密字节流
     * @author CH
     * @since 4.0.0
     */
    private class ChkURLConnection extends URLConnection {

        /**
          * chkurlconnection。
         * @param url url
         */
        protected ChkURLConnection(URL url) {
            super(url);
        }

        @Override
        public void connect() {
            // 惰性读取，无需实现
        }

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

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            jar.close();
            KeyShard.wipe(masterShards);
        }
    }
}
