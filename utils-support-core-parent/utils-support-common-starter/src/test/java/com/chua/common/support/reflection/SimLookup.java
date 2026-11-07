package com.chua.common.support.reflection;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;

/**
 * 临时：逐条验证 ReflectUtils 对跨包包级私有嵌套类（InMemoryEngineTest$User）的访问能力。
 * @author CH
 * @since 4.0.0.50
 */
public class SimLookup {

    public static void main(String[] args) throws Throwable {
        Class<?> user = Class.forName("com.chua.datasource.support.engine.InMemoryEngineTest$User");
        Method m = user.getMethod("getAge");
        System.out.println("== getAge declared=" + m + " modifiers=" + java.lang.reflect.Modifier.toString(m.getModifiers())); // [P3C E1 豁免]

        // Path used by MethodCache.findGetter:
        MethodHandle mh = ReflectUtils.findMethodHandle(user, m.getName(), m.getReturnType());
        System.out.println("== findMethodHandle(getAge, Integer): " + mh); // [P3C E1 豁免]
        if (mh != null) {
            try {
                Object v = mh.invoke(user.getDeclaredConstructor().newInstance());
                System.out.println("== invoke -> " + v); // [P3C E1 豁免]
            } catch (Throwable t) {
                System.out.println("== invoke FAILED: " + t); // [P3C E1 豁免]
            }
        }

        // Direct invoke on package-private foreign class:
        Object r = ReflectUtils.invoke(user.getDeclaredConstructor().newInstance(), "getAge", Object.class);
        System.out.println("== ReflectUtils.invoke: " + r); // [P3C E1 豁免]
    }
}
