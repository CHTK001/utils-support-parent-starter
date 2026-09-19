package com.chua.crypto.support.pack;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 打包期字节码混淆器（构建机侧，基于 ASM）
 *
 * <p>两层处理（先混淆后加密，密文内已是不可读字节码）：
 *
 * <h2>1. 调试信息剥离（默认开启，行为无损）</h2>
 * 移除 源文件 / 线数字table / 本地变量table，
 * 使 javap -c、IDE 反编译输出失去行号与局部变量名。
 *
 * <h2>2. 私有成员重命名（可选，需自行评估反射兼容性）</h2>
 * 重命名 私募 字段与方法为 p0/p1... 并同步修正类内全部引用
 * （含 lambda invokedynamic 引用的私有实现方法）。
 *
 * <h2>排除项</h2>
 * 构造器/静态块、synthetic 成员、串行版本uid、@已弃用 成员不做重命名；
 * 仅处理应用自身 类（BOOT-INF/类），依赖包整体加密不逐类改写。
 *
 * @author CH
 * @since 2026-08-26
 */
public final class ClassObfuscator {

    /**
     * 不参与重命名的保留字段
     */
    private static final Set<String> RESERVED_FIELDS = Set.of("serialVersionUID");

    /**
     * 私有构造
     */
    private ClassObfuscator() {
    }

    /**
     * 混淆单个 类 文件
     *
     * @param bytes          原始字节
     * @param renamePrivates 是否重命名私有成员
     * @return 混淆后字节
     */
    public static byte[] obfuscate(byte[] bytes, boolean renamePrivates) {
        byte[] stripped = stripDebug(bytes);
        return renamePrivates ? renamePrivates(stripped) : stripped;
    }

    /**
     * 剥离调试信息：跳过_调试 跳过 源文件/线数字table/本地变量table，
     * 类writer(读取,0) 复用常量池并原样保留 stack映射table 等帧属性
     *
     * @param bytes 原始字节
     * @return 处理后字节
     */
    public static byte[] stripDebug(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(writer, ClassReader.SKIP_DEBUG);
        return writer.toByteArray();
    }

    /**
     * 私有成员重命名
     *
     * @param bytes 原始字节
     * @return 处理后字节；无可重命名成员时原样返回
     */
    public static byte[] renamePrivates(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        String internalName = reader.getClassName();

        Map<String, String> methodRenames = new HashMap<>();
        Map<String, String> fieldRenames = new HashMap<>();
        reader.accept(new CollectorVisitor(internalName, methodRenames, fieldRenames), 0);

        if (methodRenames.isEmpty() && fieldRenames.isEmpty()) {
            return bytes;
        }

        Remapper remapper = new PrivateMemberRemapper(internalName, methodRenames, fieldRenames);
        ClassReader secondPass = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(secondPass, 0);
        secondPass.accept(new ClassRemapper(writer, remapper), ClassReader.SKIP_DEBUG);
        return writer.toByteArray();
    }

    /**
     * 第一遍扫描：收集需要重命名的私有成员
     */
    private static final class CollectorVisitor extends ClassVisitor {

        /**
         * 目标类内部名
         */
        private final String owner;

        /**
         * 方法重命名表："名称 descriptor" -> 新名
         */
        private final Map<String, String> methods;

        /**
         * 字段重命名表："名称 descriptor" -> 新名
         */
        private final Map<String, String> fields;

        /**
         * 计数器
         */
        private int counter;

        /**
         * 构造收集器
         */
        CollectorVisitor(String owner, Map<String, String> methods, Map<String, String> fields) {
            super(Opcodes.ASM9);
            this.owner = owner;
            this.methods = methods;
            this.fields = fields;
        }

        /**
         * 收集私有字段
         */
        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            if (isRenamable(access, name) && !RESERVED_FIELDS.contains(name)) {
                fields.putIfAbsent(name + " " + descriptor, nextName());
            }
            return null;
        }

        /**
         * 收集私有方法
         */
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            if (isRenamable(access, name)) {
                methods.putIfAbsent(name + " " + descriptor, nextName());
            }
            return null;
        }

        /**
         * 判断成员是否可重命名：私有、非构造/静态块、非 synthetic、非 已弃用
         * @param access access
         * @param name 名称
         * @return 是否renamable的结果
         */
        private boolean isRenamable(int access, String name) {
            boolean deprecated = (access & Opcodes.ACC_DEPRECATED) != 0;
            return (access & Opcodes.ACC_PRIVATE) != 0
                    && (access & Opcodes.ACC_SYNTHETIC) == 0
                    && !deprecated
                    && !name.startsWith("<");
        }

        /**
         * 生成顺序新名
         *
         * @return pN 形式新名
         */
        private String nextName() {
            return owner.substring(owner.lastIndexOf('/') + 1).replace('$', '_') + "_p" + counter++;
        }
    }

    /**
     * 第二遍改写：仅当引用落在同一类时替换为重命名结果
     */
    private static final class PrivateMemberRemapper extends Remapper {

        /**
         * 目标类内部名
         */
        private final String owner;

        /**
         * 方法重命名表
         */
        private final Map<String, String> methods;

        /**
         * 字段重命名表
         */
        private final Map<String, String> fields;

        /**
         * 构造改写器
         */
        PrivateMemberRemapper(String owner, Map<String, String> methods, Map<String, String> fields) {
            this.owner = owner;
            this.methods = methods;
            this.fields = fields;
        }

        /**
         * 改写方法引用（普通调用）
         */
        @Override
        public String mapMethodName(String ownerInternalName, String name, String descriptor) {
            if (owner.equals(ownerInternalName)) {
                String renamed = methods.get(name + " " + descriptor);
                if (renamed != null) {
                    return renamed;
                }
            }
            return name;
        }

        /**
         * 改写 lambda 引用的私有实现方法（invokedynamic 无 owner 信息，同类内按名+描述符匹配）
         */
        @Override
        public String mapInvokeDynamicMethodName(String name, String descriptor) {
            String renamed = methods.get(name + " " + descriptor);
            return renamed != null ? renamed : name;
        }

        /**
         * 改写字段引用
         */
        @Override
        public String mapFieldName(String ownerInternalName, String name, String descriptor) {
            if (owner.equals(ownerInternalName)) {
                String renamed = fields.get(name + " " + descriptor);
                if (renamed != null) {
                    return renamed;
                }
            }
            return name;
        }
    }
}
