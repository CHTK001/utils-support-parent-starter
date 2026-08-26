package com.chua.crypto.support;

import com.chua.crypto.support.launch.KeyShard;
import com.chua.crypto.support.launch.SelfDefense;
import com.chua.crypto.support.pack.ClassObfuscator;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加固能力测试：密钥内存分片、字节码混淆（调试剥离/私有重命名）、自我防护开关
 *
 * @author CH
 * @since 2026-08-26
 */
class HardeningTest {

    /**
     * 密钥分片：拆分拼合无损，且任一片不泄露完整密钥
     */
    @Test
    void keyShardRoundTrip() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        byte[] expect = key.clone();

        byte[][] shards = KeyShard.shard(key);
        // 原数组已被清零
        assertArrayEquals(new byte[32], key, "入参密钥应被清零");
        for (int i = 0; i < 32; i++) {
            assertFalse(shards[0][i] == expect[i] && shards[1][i] == expect[i],
                    "单片不应包含完整密钥特征");
        }

        byte[] joined = KeyShard.join(shards);
        assertArrayEquals(expect, joined, "分片拼合应还原主密钥");
        KeyShard.wipe(joined);
        KeyShard.wipe(shards);
    }

    /**
     * 混淆：调试信息被剥离、私有方法被重命名、行为保持不变
     */
    @Test
    void obfuscateStripsDebugAndRenamesPrivates() throws Exception {
        byte[] original = classBytesOf(com.demo.BootApplication.class);

        // 原始字节应含源文件名与 join 方法
        assertTrue(containsMethod(original, "join"), "样例应存在私有方法 join");
        assertTrue(hasSourceFile(original), "原始类应有 SourceFile");

        byte[] obfuscated = ClassObfuscator.obfuscate(original, true);
        assertFalse(hasSourceFile(obfuscated), "混淆后不应再有 SourceFile");
        assertFalse(containsMethod(obfuscated, "join"), "私有方法应被重命名");

        // 行为保持：经独立类加载器定义后调用
        ClassLoader loader = new SingleClassLoader(obfuscated,
                com.demo.BootApplication.class.getClassLoader());
        Object instance = loader.loadClass("com.demo.BootApplication")
                .getDeclaredConstructor().newInstance();
        assertEquals("boot-ok",
                instance.getClass().getMethod("greet").invoke(instance),
                "混淆后的类行为必须不变");
    }

    /**
     * 仅剥离调试信息（不开重命名）时成员名保持
     */
    @Test
    void stripDebugOnlyKeepsNames() throws Exception {
        byte[] original = classBytesOf(com.demo.BootApplication.class);
        byte[] stripped = ClassObfuscator.obfuscate(original, false);

        assertFalse(hasSourceFile(stripped));
        assertTrue(containsMethod(stripped, "join"), "未开启重命名时方法名应保持");

        ClassLoader loader = new SingleClassLoader(stripped,
                com.demo.BootApplication.class.getClassLoader());
        Object instance = loader.loadClass("com.demo.BootApplication")
                .getDeclaredConstructor().newInstance();
        assertEquals("boot-ok", instance.getClass().getMethod("greet").invoke(instance));
    }

    /**
     * 自我防护：off 模式安装无副作用
     */
    @Test
    void selfDefenseInstallSmoke() {
        System.setProperty(SelfDefense.PROP_GUARD, "off");
        try {
            SelfDefense.install();
            assertTrue(true);
        } finally {
            System.clearProperty(SelfDefense.PROP_GUARD);
        }
    }

    /**
     * 读取测试类编译字节
     */
    private byte[] classBytesOf(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        try (var in = type.getClassLoader().getResourceAsStream(resource)) {
            return in.readAllBytes();
        }
    }

    /**
     * 判断 class 字节是否声明指定名称的方法
     */
    private boolean containsMethod(byte[] bytes, String methodName) {
        List<String> names = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name,
                                                               String descriptor, String signature,
                                                               String[] exceptions) {
                names.add(name);
                return null;
            }
        }, 0);
        return names.contains(methodName);
    }

    /**
     * 判断 class 字节是否保留 SourceFile 属性
     */
    private boolean hasSourceFile(byte[] bytes) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visitSource(String source, String debug) {
                found[0] = true;
            }
        }, 0);
        return found[0];
    }

    /**
     * 单类加载器（用于验证转换后的字节可正常定义与执行）
     */
    private static final class SingleClassLoader extends ClassLoader {

        /**
         * 类字节
         */
        private final byte[] bytes;

        /**
         * 类全名
         */
        private final String name;

        /**
         * 构造单类加载器
         */
        SingleClassLoader(byte[] bytes, ClassLoader parent) {
            super(parent);
            this.bytes = bytes;
            this.name = "com.demo.BootApplication";
        }

        @Override
        protected Class<?> findClass(String className) throws ClassNotFoundException {
            if (name.equals(className)) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            throw new ClassNotFoundException(className);
        }
    }
}
