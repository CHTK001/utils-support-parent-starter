package com.chua.ast.support.internal;

import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Names;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.lang.reflect.Field;

/**
 * AST 处理器基类，封装了 TreeMaker 和 Names 的获取逻辑。
 * <p>
 * 子类只需实现 {@link #process(java.util.Set, javax.annotation.processing.RoundEnvironment)} 方法，
 * 通过 {@link #getTreeMaker()} 和 {@link #getNames(TreeMaker)} 获取 javac 编译树 API 的工具实例。
 * </p>
 *
 * @since 2024
 */
public abstract class AbstractAstProcessor extends AbstractProcessor {

    /**
     * Trees 实例，用于获取编译树。
     */
    protected Trees trees;

    /**
     * 消息处理器，用于输出编译期日志和警告。
     */
    protected Messager messager;

    /**
     * 编译处理环境。
     */
    protected ProcessingEnvironment processingEnv;

    /**
     * javac 上下文。
     */
    protected Context context;

    /**
     * 初始化处理器，获取 Trees 实例。
     *
     * @param processingEnv 编译处理环境
     */
    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.processingEnv = processingEnv;
        this.messager = processingEnv.getMessager();

        try {
            this.trees = Trees.instance(processingEnv);
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                    "无法获取 Trees 实例: " + e.getMessage());
        }
    }

    /**
     * 通过 JavacTrees 的 Context 获取 TreeMaker 实例。
     * <p>
     * 尝试两种方式获取 Context：方式一从 JavacProcessingEnvironment 直接获取，
     * 方式二从 JavacTrees 反射获取。
     * </p>
     *
     * @return TreeMaker 实例，获取失败返回 null
     */
    protected TreeMaker getTreeMaker() {
        if (trees == null) {
            return null;
        }

        try {
            if (context == null) {
                context = getContextFromJavacEnv();
            }
            if (context == null) {
                context = getContextFromJavacTrees();
            }
            if (context != null) {
                return TreeMaker.instance(context);
            }

            messager.printMessage(Diagnostic.Kind.WARNING, "无法获取 TreeMaker 实例");
            return null;
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                    "获取 TreeMaker 异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 方式一：从 JavacProcessingEnvironment 反射获取 Context。
     * <p>
     * 优先调用 {@code getContext()} 方法，若不存在则回退到反射读取 {@code context} 字段。
     * </p>
     *
     * @return javac Context，获取失败返回 null
     */
    private Context getContextFromJavacEnv() {
        try {
            Class<?> javacEnvClass = Class.forName(
                    "com.sun.tools.javac.processing.JavacProcessingEnvironment");
            if (!javacEnvClass.isInstance(processingEnv)) {
                return null;
            }

            try {
                java.lang.reflect.Method getContextMethod = javacEnvClass.getMethod("getContext");
                return (Context) getContextMethod.invoke(processingEnv);
            } catch (NoSuchMethodException nsme) {
                Field contextField = javacEnvClass.getDeclaredField("context");
                contextField.setAccessible(true);
                return (Context) contextField.get(processingEnv);
            }
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "方式一获取 Context 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 方式二：从 JavacTrees 反射获取 Context。
     * <p>
     * 依次尝试 {@code context}、{@code treeContext} 字段名，返回第一个成功读取的值。
     * </p>
     *
     * @return javac Context，获取失败返回 null
     */
    private Context getContextFromJavacTrees() {
        JavacTrees javacTrees = (JavacTrees) trees;
        String[] fieldNames = new String[]{"context", "treeContext"};

        for (String fieldName : fieldNames) {
            try {
                Field contextField = JavacTrees.class.getDeclaredField(fieldName);
                contextField.setAccessible(true);
                return (Context) contextField.get(javacTrees);
            } catch (Exception e) {
                // 字段名不匹配或访问被拒，继续尝试下一个
            }
        }
        return null;
    }

    /**
     * 从 Context 获取 Names 实例。
     * <p>
     * 优先从已获取的 Context 中获取 Names，若 Context 为空则从 TreeMaker 中反射获取。
     * </p>
     *
     * @param maker TreeMaker 实例
     * @return Names 实例，获取失败返回 null
     */
    protected Names getNames(TreeMaker maker) {
        if (context != null) {
            return Names.instance(context);
        }

        try {
            Field contextField = maker.getClass().getDeclaredField("context");
            contextField.setAccessible(true);
            return Names.instance((Context) contextField.get(maker));
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                    "获取 Names 异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 检查 Trees API 是否可用。
     *
     * @return Trees 实例不为 null 时返回 true
     */
    protected boolean isTreeApiAvailable() {
        return trees != null;
    }

    /**
     * 输出编译期提示信息。
     *
     * @param msg     提示消息
     * @param element 关联的编译元素
     */
    protected void note(String msg, Element element) {
        messager.printMessage(Diagnostic.Kind.NOTE, msg, element);
    }

    /**
     * 输出编译期警告信息。
     *
     * @param msg     警告消息
     * @param element 关联的编译元素
     */
    protected void warn(String msg, Element element) {
        messager.printMessage(Diagnostic.Kind.WARNING, msg, element);
    }
}
