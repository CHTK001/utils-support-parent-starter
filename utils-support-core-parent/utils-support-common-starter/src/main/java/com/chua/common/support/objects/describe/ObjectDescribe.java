package com.chua.common.support.objects.describe;

import com.chua.common.support.utils.ClassUtils;
import lombok.Getter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对象描述，封装对对象的反射操作，提供便捷的方法访问。
 *
 * @author CH
 * @since 2024/12/20
 */
@Getter
public class ObjectDescribe {

    /** Object */
    private final Object object;
    private final Class<?> objectClass;

    private ObjectDescribe(Object object) {
        this.object = object;
        this.objectClass = object != null ? object.getClass() : null;
    }

    public static ObjectDescribe of(Object object) {
        return new ObjectDescribe(object);
    }

    /** 获取方法描述 */
    public MethodDescribe getMethodDescribe(String methodName) {
        if (objectClass == null || methodName == null) { return null; }
        Method method = findMethod(methodName);
        return method != null ? new MethodDescribe(object, method) : null;
    }

    /** 获取所有方法描述 */
    public List<MethodDescribe> getMethodDescribes() {
        if (objectClass == null) { return Collections.emptyList(); }
        List<Method> methods = ClassUtils.getLocalMethods(objectClass);
        List<MethodDescribe> describes = new ArrayList<>();
        for (Method method : methods) {
            describes.add(new MethodDescribe(object, method));
        }
        return describes;
    }

    /** 按名称查找方法 */
    private Method findMethod(String methodName) {
        if (objectClass == null || methodName == null) { return null; }
        for (Method method : ClassUtils.getLocalMethods(objectClass)) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        return null;
    }
}