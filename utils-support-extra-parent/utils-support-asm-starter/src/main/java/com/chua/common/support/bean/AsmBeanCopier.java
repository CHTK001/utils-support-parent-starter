package com.chua.common.support.bean;

import com.chua.common.support.lang.bean.BeanCopier;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.bean.JdkBeanCopier;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.reflection.ReflectUtils;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
* ASM 字节码实现的 Bean 属性拷贝器。
*
* <p>通过 ASM 在运行时为目标 (Source, Target) 类型对生成优化的字节码拷贝类，
* 直接调用 getter/setter 方法，避免反射开销，性能最优。</p>
*
* <p>生成策略：对每组 (sourceClass, targetClass) 生成一个专用的拷贝类，
* 在字节码层面直接调用 getter/setter，已生成的类会缓存复用。</p>
*
* @author CH
* @since 1.0.0
 */
@Spi("asm")
@SuppressWarnings("ALL")
public class AsmBeanCopier implements BeanCopier {

    /** Bean 拷贝器缓存 */
    private static final Map<List<Class<?>>, BeanCopier> GENERATED_CACHE = new ConcurrentHashMap<>();

    /** 降级使用的 Bean 拷贝器 */
    private static final BeanCopier FALLBACK = new JdkBeanCopier();

    @Override
    /** 复制属性 */
    public void copyProperties(Object source, Object target) {
        copyProperties(source, target, (String[]) null);
    }

    @Override
    /** 复制属性 */
    public void copyProperties(Object source, Object target, String... ignoreProperties) {
        if (source == null || target == null) {
            return;
        }

        List<Class<?>> key = Arrays.asList(source.getClass(), target.getClass());
        BeanCopier generated = GENERATED_CACHE.computeIfAbsent(key, k -> generateCopier(k.getFirst(), k.get(1)));

        if (generated != null) {
            generated.copyProperties(source, target, ignoreProperties);
        } else {
            FALLBACK.copyProperties(source, target, ignoreProperties);
        }
    }

    @Override
    /** 复制属性 */
    public void copyProperties(Map<String, Object> sourceMap, Object target) {
        FALLBACK.copyProperties(sourceMap, target);
    }

    @Override
    /** 复制属性 */
    public void copyProperties(Object source, Map<String, Object> target) {
        FALLBACK.copyProperties(source, target);
    }

    /**
    * generatecopier
    *
    * @param sourceClass 源类
    * @param targetClass 目标类
    * @return generateCopier的结果
    */
    private BeanCopier generateCopier(Class<?> sourceClass, Class<?> targetClass) {
        try {
            Map<String, PropertyDescriptor> sourceReads = new LinkedHashMap<>();
            Map<String, PropertyDescriptor> targetWrites = new LinkedHashMap<>();
            resolveProperties(sourceClass, sourceReads, null);
            resolveProperties(targetClass, null, targetWrites);

            // 找出匹配的属性
            List<PropertyPair> matched = new ArrayList<>();
            for (Map.Entry<String, PropertyDescriptor> entry : sourceReads.entrySet()) {
                PropertyDescriptor targetPd = targetWrites.get(entry.getKey());
                if (targetPd != null && entry.getValue().getReadMethod() != null && targetPd.getWriteMethod() != null) {
                    matched.add(new PropertyPair(
                            entry.getValue().getReadMethod(),
                            targetPd.getWriteMethod()
                    ));
                }
            }

            if (matched.isEmpty()) {
                return null;
            }

            return createCopierClass(sourceClass, targetClass, matched);
        } catch (Exception e) {
            return null;
        }
    }

    /**
    * 创建copier类
    * @param sourceClass 源类
    * @param targetClass 目标类
    * @param matched 匹配
    */
    private BeanCopier createCopierClass(Class<?> sourceClass, Class<?> targetClass,
                                          List<PropertyPair> matched) throws Exception {
        String generatedName = "com/chua/common/support/bean/GeneratedCopier_"
                + sourceClass.getSimpleName() + "_" + targetClass.getSimpleName()
                + "_" + Integer.toHexString(System.identityHashCode(matched));

        String sourceInternal = Type.getInternalName(sourceClass);
        String targetInternal = Type.getInternalName(targetClass);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, generatedName, null,
                "java/lang/Object", new String[]{"com/chua/common/support/bean/BeanCopier"});

        // 默认构造方法
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // 生成 copyProperties(Object, Object) 方法
        String delegateDesc = "(Ljava/util/Set;)V";
        String staticCopyDesc = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/Set;)V";

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "copyProperties",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/chua/common/support/bean/BeanCopier", "copyProperties",
                "(Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/String;)V", true);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // 生成 copyProperties(Object, Object, String[]) 方法
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "copyProperties",
                "(Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/String;)V", null, null);
        mv.visitCode();

        // 如果 source == null 或 target == null，直接返回
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label ifNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, ifNull);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitJumpInsn(Opcodes.IFNULL, ifNull);

 // 构建 ignore 设置
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        Label noIgnores = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, noIgnores);

        // new HashSet<>(Arrays.asList(ignoreProperties))
        mv.visitTypeInsn(Opcodes.NEW, "java/util/HashSet");
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.NEW, "java/util/Arrays");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "asList",
                "([Ljava/lang/Object;)Ljava/util/List;", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>",
                "(Ljava/util/Collection;)V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        Label afterIgnores = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, afterIgnores);

        mv.visitLabel(noIgnores);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, 4);

        mv.visitLabel(afterIgnores);

 // 强制转换 源 和 Target
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, sourceInternal);
        mv.visitVarInsn(Opcodes.ASTORE, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, targetInternal);
        mv.visitVarInsn(Opcodes.ASTORE, 6);

        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, generatedName, "internalCopy",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/Set;)V", false);

        mv.visitLabel(ifNull);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // 生成 static internalCopy(Object, Object, Set) 方法
        mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "internalCopy",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/Set;)V", null, null);
        mv.visitCode();

 // 本地 变量分配:
        // 0 = source (Object), 1 = target (Object), 2 = ignoreSet (Set)
        // 需要重新加载并转换
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, sourceInternal);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, targetInternal);
        mv.visitVarInsn(Opcodes.ASTORE, 4);

        for (PropertyPair pair : matched) {
            Method getter = pair.getter;
            Method setter = pair.setter;
            String propName = getPropertyName(getter);

            // 检查 ignore set：如果 ignoreSet != null 且包含当前属性名则跳过
            Label doCopy = new Label();
            Label skipCopy = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitJumpInsn(Opcodes.IFNULL, doCopy);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitLdcInsn(propName);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "contains",
                    "(Ljava/lang/Object;)Z", true);
            mv.visitJumpInsn(Opcodes.IFEQ, doCopy);
            mv.visitJumpInsn(Opcodes.GOTO, skipCopy);

            mv.visitLabel(doCopy);

            // 调用 getter
            Class<?> returnType = getter.getReturnType();
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, sourceInternal,
                    getter.getName(), Type.getMethodDescriptor(getter), false);

            // 保存到临时变量
            int tempVar = 5;
            if (returnType.isPrimitive()) {
                if (returnType == int.class) {
                    mv.visitVarInsn(Opcodes.ISTORE, tempVar);
                } else if (returnType == long.class) {
                    mv.visitVarInsn(Opcodes.LSTORE, tempVar);
                    tempVar += 1;
                } else if (returnType == double.class) {
                    mv.visitVarInsn(Opcodes.DSTORE, tempVar);
                    tempVar += 1;
                } else if (returnType == float.class) {
                    mv.visitVarInsn(Opcodes.FSTORE, tempVar);
                } else if (returnType == boolean.class) {
                    mv.visitVarInsn(Opcodes.ISTORE, tempVar);
                } else {
                    mv.visitVarInsn(Opcodes.ISTORE, tempVar);
                }
            } else {
                mv.visitVarInsn(Opcodes.ASTORE, tempVar);
            }

 // 加载 Target
            mv.visitVarInsn(Opcodes.ALOAD, 4);

            // 加载 getter 返回值
            if (returnType.isPrimitive()) {
                if (returnType == int.class) {
                    mv.visitVarInsn(Opcodes.ILOAD, 5);
                } else if (returnType == long.class) {
                    mv.visitVarInsn(Opcodes.LLOAD, 5);
                } else if (returnType == double.class) {
                    mv.visitVarInsn(Opcodes.DLOAD, 5);
                } else if (returnType == float.class) {
                    mv.visitVarInsn(Opcodes.FLOAD, 5);
                } else if (returnType == boolean.class) {
                    mv.visitVarInsn(Opcodes.ILOAD, 5);
                } else {
                    mv.visitVarInsn(Opcodes.ILOAD, 5);
                }
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 5);
            }

            // 调用 setter
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, targetInternal,
                    setter.getName(), Type.getMethodDescriptor(setter), false);

            mv.visitLabel(skipCopy);
        }

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();

        byte[] classBytes = cw.toByteArray();
        GeneratedClassLoader loader = new GeneratedClassLoader(
                Thread.currentThread().getContextClassLoader());
        Class<?> copierClass = loader.defineClass(
                generatedName.replace('/', '.'), classBytes);
        return (BeanCopier) ReflectUtils.instantiate(copierClass);
    }

    /**
    * 解析属性
    * @param clazz clazz
    * @param reads 读取
    * @param writes 写入
    */
    private void resolveProperties(Class<?> clazz,
                                    Map<String, PropertyDescriptor> reads,
                                    Map<String, PropertyDescriptor> writes) {
        try {
            BeanInfo beanInfo = Introspector.getBeanInfo(clazz, Object.class);
            for (PropertyDescriptor pd : beanInfo.getPropertyDescriptors()) {
                if (reads != null && pd.getReadMethod() != null) {
                    reads.put(pd.getName(), pd);
                }
                if (writes != null && pd.getWriteMethod() != null) {
                    writes.put(pd.getName(), pd);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
    * 获取财产名称
    *
    * @param getter getter
    * @return 获取财产名称的结果
    * @author CH
    * @since 4.0.0
    */
    private static String getPropertyName(Method getter) {
        String name = getter.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        if (name.startsWith("is") && name.length() > 2) {
            return Character.toLowerCase(name.charAt(2)) + name.substring(3);
        }
        return name;
    }

    static class PropertyPair {
        final Method getter; // getter
        final Method setter; // setter

        PropertyPair(Method getter, Method setter) {
            this.getter = getter;
            this.setter = setter;
        }
    }

    /**
    * 生成的字节码类的类加载器。
    * @author CH
    * @since 4.0.0
    */
    static class GeneratedClassLoader extends ClassLoader {
        GeneratedClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> defineClass(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    @Override
    /** 判断相等 */
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o != null && getClass() == o.getClass();
    }

    @Override
    /** 哈希编码 */
    public int hashCode() {
        return getClass().hashCode();
    }
}
