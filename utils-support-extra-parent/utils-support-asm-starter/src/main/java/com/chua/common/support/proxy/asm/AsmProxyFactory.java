package com.chua.common.support.proxy.asm;

import com.chua.common.support.proxy.ProxyFactory;
import com.chua.common.support.proxy.intercept.MethodIntercept;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import lombok.SneakyThrows;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * ASM 代理工厂，基于 ASM 字节码框架直接生成代理类。
 *
 * <p>与 JDK 动态代理和 Javassist 不同，ASM 直接在字节码层面操作，
 * 性能最优但代码复杂度最高。适用于对代理性能有极致要求的场景。</p>
 *
 * @param <T> 代理类型
 * @author CH
 * @since 2025/7/20
 */
@Spi("asm")
@SuppressWarnings("ALL")
public class AsmProxyFactory<T> implements ProxyFactory<T> {

    /**
     * 单例实例
     */
    public static final ProxyFactory INSTANCE = new AsmProxyFactory();

    @Override
    @SneakyThrows
    /**
     * 创建Proxy
     * @param target target
     * @param interfaces interfaces
     * @param classLoader classLoader
     * @param intercept intercept
     */
    public T createProxy(Class<T> target, Class<?>[] interfaces, ClassLoader classLoader,
                        MethodIntercept<T> intercept) {
        // 使用 JDK 动态代理作为 ASM 实现的回退
        if (target.isInterface()) {
            return ReflectUtils.newProxy(
                target.getClassLoader(),
                new Class<?>[]{target},
                (proxy, method, args) -> intercept.invoke(proxy, method, args, (T) proxy));
        }

        String proxyClassName = target.getName() + "$AsmProxy";
        String targetInternalName = Type.getInternalName(target);
        String proxyInternalName = targetInternalName + "$AsmProxy";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, proxyInternalName, null, targetInternalName, null);

        // 添加拦截器字段
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "INTERCEPT",
                Type.getDescriptor(MethodIntercept.class), null, null).visitEnd();

        // 默认构造方法
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, targetInternalName, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // 生成代理方法
        for (Method method : target.getMethods()) {
            if (method.isDefault() || method.getDeclaringClass() == Object.class) {
                continue;
            }
            String methodDesc = Type.getMethodDescriptor(method);
            Class<?>[] paramTypes = method.getParameterTypes();
            Class<?> returnType = method.getReturnType();

            mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method.getName(), methodDesc, null, null);
            mv.visitCode();

            // 加载拦截器
            mv.visitFieldInsn(Opcodes.GETSTATIC, proxyInternalName, "INTERCEPT",
                    Type.getDescriptor(MethodIntercept.class));

            // 第一个参数：this
            mv.visitVarInsn(Opcodes.ALOAD, 0);

            // 构建 Method 对象
            mv.visitLdcInsn(Type.getObjectType(proxyInternalName));
            mv.visitLdcInsn(method.getName());
            mv.visitIntInsn(Opcodes.BIPUSH, paramTypes.length);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
            for (int i = 0; i < paramTypes.length; i++) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitIntInsn(Opcodes.BIPUSH, i);
                mv.visitLdcInsn(Type.getType(paramTypes[i]));
                mv.visitInsn(Opcodes.AASTORE);
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/reflect/Proxy", "getMethod",
                    "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);

            // 第三个参数：args 数组
            mv.visitIntInsn(Opcodes.BIPUSH, paramTypes.length);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            for (int i = 0; i < paramTypes.length; i++) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitIntInsn(Opcodes.BIPUSH, i);
                mv.visitVarInsn(Type.getType(paramTypes[i]).getOpcode(Opcodes.ILOAD), i + 1);
                mv.visitInsn(Opcodes.AASTORE);
            }

            // 第四个参数：proxy(this)
            mv.visitVarInsn(Opcodes.ALOAD, 0);

            // 调用 intercept.invoke()
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                    Type.getInternalName(MethodIntercept.class), "invoke",
                    "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);

            // 处理返回类型
            if (returnType == void.class) {
                mv.visitInsn(Opcodes.POP);
                mv.visitInsn(Opcodes.RETURN);
            } else if (returnType.isPrimitive()) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(ClassUtils.primitiveToWrapper(returnType)));
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(ClassUtils.primitiveToWrapper(returnType)),
                        returnType.getName() + "Value", "()" + Type.getDescriptor(returnType), false);
                mv.visitInsn(Type.getType(returnType).getOpcode(Opcodes.IRETURN));
            } else {
                mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(returnType));
                mv.visitInsn(Opcodes.ARETURN);
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        cw.visitEnd();

        byte[] classBytes = cw.toByteArray();

        // 使用自定义类加载器加载生成的字节码
        ClassLoader proxyClassLoader = new ClassLoader(classLoader) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(proxyClassName)) {
                    return defineClass(name, classBytes, 0, classBytes.length);
                }
                return super.findClass(name);
            }
        };

        Class<?> proxyClass = proxyClassLoader.loadClass(proxyClassName);
        T instance = ClassUtils.newInstance((Class<T>) proxyClass);

        // 设置拦截器
        java.lang.reflect.Field field = proxyClass.getDeclaredField("INTERCEPT");
        field.setAccessible(true);
        field.set(null, intercept);

        return instance;
    }
}
