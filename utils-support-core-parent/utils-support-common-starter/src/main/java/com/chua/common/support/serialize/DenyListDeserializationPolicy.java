package com.chua.common.support.serialize;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;

/**
 * 内置高危反序列化类黑名单策略。
 *
 * <p>覆盖 JDK 原生、Commons Collections 2/3/4、Commons BeanUtils/FileUpload、Spring、
 * Groovy、Jython、Rhino、XStream 生态、SnakeYAML、日志组件、c3p0、Druid、Fastjson 1/2、
 * JBoss、Caucho/Hessian、Dubbo、RMI/JNDI/JMX 等已知 gadget 链入口。</p>
 *
 * <p>采用前缀匹配，只拦截"按类名还原对象图"的反序列化动作，不影响这些类被正常调用。
 * 业务确需放行时，注册更高优先级的自定义 {@link DeserializationPolicy} 无法放宽本策略
 * （策略之间是 AND 语义），应当改为使用不带类名的文本序列化（如 JSON）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("denylist")
@SpiDefault
public class DenyListDeserializationPolicy implements DeserializationPolicy {

    /**
     * 高危反序列化 gadget 类前缀黑名单
     */
    private static final String[] DENIED_CLASS_PREFIXES = {
            "com.sun.",
            "sun.rmi.",
            "jdk.internal.",
            "java.rmi.",
            "java.lang.invoke.",
            "javax.naming.",
            "javax.management.",
            "javax.script.",
            "javax.el.",
            "org.apache.commons.collections.",
            "org.apache.commons.collections4.",
            "org.apache.commons.beanutils.",
            "org.apache.commons.fileupload.",
            "org.apache.commons.jxpath.",
            "org.apache.xbean.",
            "org.apache.velocity.",
            "org.apache.bcel.",
            "org.apache.logging.log4j.",
            "ch.qos.logback.",
            "org.springframework.beans.factory.",
            "org.springframework.context.",
            "org.springframework.aop.",
            "org.codehaus.groovy.",
            "groovy.",
            "bsh.",
            "org.yaml.snakeyaml.",
            "com.mchange.v2.c3p0.",
            "com.alibaba.fastjson.",
            "com.alibaba.fastjson2.",
            "com.alibaba.druid.",
            "net.sf.json.",
            "org.jboss.",
            "org.python.core.",
            "org.mozilla.javascript.",
            "org.apache.dubbo.",
            "com.caucho.",
            "org.h2.",
            "org.jdom2.",
            "com.thoughtworks.xstream."
    };

    /**
     * 判断类名是否命中黑名单前缀。
     *
     * @param className 待还原的类全名
     * @return 未命中黑名单返回 {@code true}
     */
    @Override
    public boolean allows(String className) {
        if (className == null || className.isEmpty()) {
            return true;
        }
        for (String denied : DENIED_CLASS_PREFIXES) {
            if (className.startsWith(denied)) {
                return false;
            }
        }
        return true;
    }
}
