package com.chua.common.support.lang.compile;

import com.chua.common.support.constant.CommonConstant;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullUnmarked;


/**
 * Java 代码编译器接口，用于动态编译和加载 Java 类。
 * 该接口提供了从源代码字符串中提取包名、类名以及执行编译的核心功能。
 *
 * @author CHTK
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public interface Compiler {

    /**
     * 匹配 "extends" 关键字后跟随的父类名称的正则表达式模式。
     * 捕获组 1 将包含父类的全限定名或简单名。
     */
    Pattern PARENT_PATTERN = Pattern.compile("extends\\s+([a-zA-z][$_a-zA-z0-9.]*)");
    
    /**
     * 匹配 "implements" 关键字后跟随的接口名称的正则表达式模式。
     * 捕获组 1 将包含接口的全限定名或简单名。
     */
    Pattern INTERFACE_PATTERN = Pattern.compile("implements\\s+([a-zA-z][$_a-zA-z0-9.]*)");
    
    /**
     * 匹配 "package" 声明后的包名的正则表达式模式。
     * 捕获组 1 将包含包名。
     */
    Pattern PACKAGE_PATTERN = Pattern.compile("package\\s+([a-zA-z][$_a-zA-z0-9.]*)");
    
    /**
     * 匹配 "class" 关键字后跟随的类名的正则表达式模式。
     * 捕获组 1 将包含类名（不包含泛型参数）。
     */
    Pattern CLASS_PATTERN = Pattern.compile("class\\s+([$_a-zA-z][$_a-zA-z0-9]*)");
    
    /**
     * 匹配 "import" 语句的正则表达式模式。
     * 捕获组 1 将包含导入的完整路径（不含分号）。
     */
    Pattern IMPORT_PATTERN = Pattern.compile("import\\s+(.*);");
    
    /**
     * 匹配字段声明（private, public, protected）的正则表达式模式。
     * 捕获组 1 为访问修饰符，捕获组 2 为字段类型和名称。
     * 注意：原模式中 "protect" 应为 "protected"，此处保留原逻辑。
     */
    Pattern FIELD_PATTERN = Pattern.compile("(private|public|protect)\\s+(.*);");
    
    /**
     * 匹配方法声明的正则表达式模式。
     * 结构复杂，旨在捕获访问修饰符、返回类型（含泛型）、方法名、参数列表及方法体。
     * 注意：此正则表达式较为宽泛，可能无法完美匹配所有复杂的 Java 语法场景。
     */
    Pattern METHOD_PATTERN = Pattern.compile("(private|public|protect)\\s+(([a-zA-z][$_a-zA-z0-9.]*)(<(.*?)>)*)\\s+([a-zA-z][$_a-zA-z0-9.]*)(\\s+)*\\((.*)\\)(\\s+)*\\{((.*?)|\n)*}");

    /**
     * 使用当前线程上下文类加载器编译给定的 Java 源代码字符串。
     *
     * @param code 要编译的 Java 源代码字符串
     * @return 编译并加载后的 Class 对象
     */
    default Class<?> compiler(String code) {
        return compiler(code, Thread.currentThread().getContextClassLoader());
    }

    /**
     * 使用指定的类加载器编译给定的 Java 源代码字符串。
     *
     * @param code      要编译的 Java 源代码字符串
     * @param classLoader 用于加载类的类加载器
     * @return 编译并加载后的 Class 对象
     */
    default Class<?> compiler(String code, final ClassLoader classLoader) {
        return compiler(code, classLoader, "");
    }

    /**
     * 核心编译方法：解析源代码中的包名和类名，检查是否已存在该类，若不存在则调用 doCompile 进行动态编译。
     *
     * @param code      要编译的 Java 源代码字符串
     * @param classLoader 用于加载类的类加载器
     * @param suffix    类名后缀，用于区分动态生成的类
     * @return 编译并加载后的 Class 对象
     * @throws IllegalStateException 如果代码格式错误（如缺少结束大括号）或编译失败
     * @throws IllegalArgumentException 如果代码中未找到类名定义
     */
    default Class<?> compiler(String code, final ClassLoader classLoader, final String suffix) {
        // 去除首尾空白字符
        code = code.trim();
        
        // 尝试提取包名
        Matcher matcher = PACKAGE_PATTERN.matcher(code);
        String pkg;
        if (matcher.find()) {
            pkg = matcher.group(1);
        } else {
            pkg = "";
        }
        
        // 尝试提取类名
        matcher = CLASS_PATTERN.matcher(code);
        String cls;
        if (matcher.find()) {
            cls = matcher.group(1);
        } else {
            throw new IllegalArgumentException("No such class name in " + code);
        }
        
        // 组合完整的类名：包名 + "." + 类名 + 后缀
        String className = (pkg != null && pkg.length() > 0 ? pkg + "." + cls : cls) + suffix;
        
        try {
            // 首先尝试通过 Class.forName 查找是否已存在该类（通常用于缓存命中）
            return Class.forName(className, true, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            // 如果找不到类，检查代码是否以右大括号结尾，这是有效 Java 代码块的基本特征
            if (!code.endsWith(CommonConstant.SYMBOL_RIGHT_BIG_PARENTHESES)) {
                throw new IllegalStateException("The java code not endsWith \"}\", code: \n" + code + "\n");
            }
            
            // 调用子实现进行实际的动态编译操作
            try {
                return doCompile(className, code);
            } catch (RuntimeException t) {
                // 直接抛出运行时异常
                throw t;
            } catch (Throwable t) {
                // 捕获其他异常并包装为带有详细信息的 IllegalStateException
                throw new IllegalStateException("Failed to compile class, cause: " + t.getMessage() + ", class: " + className + ", code: \n" + code);
            }
        }
    }

    /**
     * 执行实际的编译操作。此方法必须由具体的实现类提供。
     *
     * @param name   要编译的类的全限定名
     * @param source 原始的 Java 源代码字符串
     * @return 编译后的 Class 对象
     * @throws Throwable 编译过程中可能抛出的任何异常
     */
    Class<?> doCompile(String name, String source) throws Throwable;

    /**
     * 从 Java 源代码字符串中提取类名。
     *
     * @param code 包含类定义的 Java 源代码字符串
     * @return 提取到的类名
     * @throws IllegalArgumentException 如果代码中未找到类名定义
     */
    default String getClassName(String code) {
        // 使用 CLASS_PATTERN 匹配类名
        Matcher matcher1 = CLASS_PATTERN.matcher(code);
        if (matcher1.find()) {
            return matcher1.group(1);
        } else {
            throw new IllegalArgumentException("No such class name in \n" + code);
        }
    }

    /**
     * 从 Java 源代码字符串中提取包名。
     *
     * @param code 包含包声明的 Java 源代码字符串
     * @return 提取到的包名，如果未找到则返回 null
     */
    default String getPkg(String code) {
        // 使用 PACKAGE_PATTERN 匹配包名
        Matcher matcher = PACKAGE_PATTERN.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

}