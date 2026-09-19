package com.chua.ast.support.internal;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.lang.reflect.Field;

/**
 * AST 工具类，封装 javac 树 API 的常用操作
 *
 * <p>提供从 {@code com.sun.tools.javac.*} 内部 API 获取 TreeMaker、Names 等核心工具的方法。
 * 所有方法均为静态方法，无需实例化即可使用。</p>
 *
 * @author CH
 * @since 2024
 */
public final class AstUtils {

    /** 创建 ast工具 实例 */
    private AstUtils() {
    }

    // ========================================================================
    // 核心工具方法
    // ========================================================================

    /**
     * 获取 树maker 实例
     *
     * @param env 编译处理环境
     * @return TreeMaker 实例
     */
    public static com.sun.tools.javac.tree.TreeMaker treeMaker(ProcessingEnvironment env) {
        com.sun.tools.javac.util.Context context = javacContext(env);
        return com.sun.tools.javac.tree.TreeMaker.instance(context);
    }

    /**
     * 获取 名称 实例
     *
     * @param env 编译处理环境
     * @return Names 实例
     */
    public static com.sun.tools.javac.util.Names names(ProcessingEnvironment env) {
        com.sun.tools.javac.util.Context context = javacContext(env);
        return com.sun.tools.javac.util.Names.instance(context);
    }

    /**
     * 从编译处理环境中获取 javac 上下文 实例
     *
     * <p>通过 JavacTrees 反射获取 Context 对象。</p>
     *
     * @param env 编译处理环境
     * @return javac 上下文 实例
     */
    public static com.sun.tools.javac.util.Context javacContext(ProcessingEnvironment env) {
        com.sun.tools.javac.api.JavacTrees javacTrees =
                (com.sun.tools.javac.api.JavacTrees) com.sun.source.util.Trees.instance(env);
        try {
            Field contextField = com.sun.tools.javac.api.JavacTrees.class.getDeclaredField("context"); // [P3C 1.10 豁免] javac 内部类私有字段适配，本模块未依赖 utils-support-common-starter，无 ReflectUtils 可用
            contextField.setAccessible(true);
            return (com.sun.tools.javac.util.Context) contextField.get(javacTrees);
        } catch (Exception e) {
            throw new RuntimeException("无法获取 javac Context", e);
        }
    }

    /**
     * 将 Element 转换为 jc方法decl
     *
     * @param methodElement 方法元素
     * @param env           编译处理环境
     * @param messager      消息处理器
     * @return JCMethodDecl 实例，转换失败返回 空
     */
    public static com.sun.tools.javac.tree.JCTree.JCMethodDecl asJcMethod(Element methodElement, ProcessingEnvironment env, Messager messager) {
        com.sun.source.util.Trees trees = com.sun.source.util.Trees.instance(env);
        com.sun.source.tree.Tree methodTree = trees.getTree(methodElement);
        if (methodTree instanceof com.sun.tools.javac.tree.JCTree.JCMethodDecl jc) {
            return jc;
        }
        messager.printMessage(Diagnostic.Kind.WARNING, "无法将 Element 转换为 JCMethodDecl");
        return null;
    }

    // ========================================================================
    // 表达式构造方法
    // ========================================================================

    /**
     * 创建 空 字面量表达式
     *
     * @param maker 树maker 实例
     * @return null 字面量
     */
    public static com.sun.tools.javac.tree.JCTree.JCLiteral nullLiteral(com.sun.tools.javac.tree.TreeMaker maker) {
        return maker.Literal(com.sun.tools.javac.code.TypeTag.BOT, null);
    }

    /**
     * 创建标识符表达式
     *
     * @param maker 树maker 实例
     * @param names 名称 实例
     * @param name  标识符名称
     * @return 标识符表达式
     */
    public static com.sun.tools.javac.tree.JCTree.JCIdent ident(com.sun.tools.javac.tree.TreeMaker maker,
                                                                com.sun.tools.javac.util.Names names,
                                                                String name) {
        return maker.Ident(names.fromString(name));
    }

    /**
     * 创建相等比较表达式 {@code left == right}
     *
     * @param maker 树maker 实例
     * @param left  左操作数
     * @param right 右操作数
     * @return 相等比较表达式
     */
    public static com.sun.tools.javac.tree.JCTree.JCBinary eq(com.sun.tools.javac.tree.TreeMaker maker,
                                                              com.sun.tools.javac.tree.JCTree.JCExpression left,
                                                              com.sun.tools.javac.tree.JCTree.JCExpression right) {
        return maker.Binary(com.sun.tools.javac.tree.JCTree.Tag.EQ, left, right);
    }

    /**
     * 创建不等比较表达式 {@code left != right}
     *
     * @param maker 树maker 实例
     * @param left  左操作数
     * @param right 右操作数
     * @return 不等比较表达式
     */
    public static com.sun.tools.javac.tree.JCTree.JCBinary ne(com.sun.tools.javac.tree.TreeMaker maker,
                                                              com.sun.tools.javac.tree.JCTree.JCExpression left,
                                                              com.sun.tools.javac.tree.JCTree.JCExpression right) {
        return maker.Binary(com.sun.tools.javac.tree.JCTree.Tag.NE, left, right);
    }

    /**
     * 创建表达式语句
     *
     * @param maker 树maker 实例
     * @param expr  表达式
     * @return 表达式语句
     */
    public static com.sun.tools.javac.tree.JCTree.JCExpressionStatement exec(com.sun.tools.javac.tree.TreeMaker maker,
                                                                             com.sun.tools.javac.tree.JCTree.JCExpression expr) {
        return maker.Exec(expr);
    }

    /**
     * 创建赋值表达式 {@code var = value}
     *
     * @param maker 树maker 实例
     * @param lhs   左值
     * @param rhs   右值
     * @return 赋值表达式
     */
    public static com.sun.tools.javac.tree.JCTree.JCAssign assign(com.sun.tools.javac.tree.TreeMaker maker,
                                                                  com.sun.tools.javac.tree.JCTree.JCExpression lhs,
                                                                  com.sun.tools.javac.tree.JCTree.JCExpression rhs) {
        return maker.Assign(lhs, rhs);
    }

    /**
     * 创建代码块
     *
     * @param maker 树maker 实例
     * @param stats 语句列表
     * @return 代码块
     */
    @SafeVarargs
    public static com.sun.tools.javac.tree.JCTree.JCBlock block(com.sun.tools.javac.tree.TreeMaker maker,
                                                                com.sun.tools.javac.tree.JCTree.JCStatement... stats) {
        return maker.Block(0, com.sun.tools.javac.util.List.from(stats));
    }

    /**
     * 创建 if 语句
     *
     * @param maker     树maker 实例
     * @param condition 条件表达式
     * @param thenBlock 然后 代码块
     * @param elseBlock else 语句，可为 空
     * @return if 语句
     */
    public static com.sun.tools.javac.tree.JCTree.JCIf ifStmt(com.sun.tools.javac.tree.TreeMaker maker,
                                                              com.sun.tools.javac.tree.JCTree.JCExpression condition,
                                                              com.sun.tools.javac.tree.JCTree.JCBlock thenBlock,
                                                              com.sun.tools.javac.tree.JCTree.JCStatement elseBlock) {
        return maker.If(condition, thenBlock, elseBlock);
    }

    /**
     * 创建 抛出 语句 {@code throw new XxxException(message)}
     *
     * @param maker              树maker 实例
     * @param names              名称 实例
     * @param exceptionClassName 异常类名
     * @param message            异常消息
     * @return throw 语句
     */
    public static com.sun.tools.javac.tree.JCTree.JCThrow throwStmt(com.sun.tools.javac.tree.TreeMaker maker,
                                                                    com.sun.tools.javac.util.Names names,
                                                                    String exceptionClassName,
                                                                    String message) {
        // new XxxException(message)
        com.sun.tools.javac.tree.JCTree.JCIdent exceptionIdent =
                maker.Ident(names.fromString(exceptionClassName));
        com.sun.tools.javac.tree.JCTree.JCLiteral msgLiteral =
                maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS, message);
        com.sun.tools.javac.tree.JCTree.JCNewClass newExpr =
                maker.NewClass(null, com.sun.tools.javac.util.List.nil(),
                        exceptionIdent, com.sun.tools.javac.util.List.of(msgLiteral), null);
        return maker.Throw(newExpr);
    }

    /**
     * 在方法体的开头插入语句
     *
     * @param jcMethod 方法声明
     * @param stats    要插入的语句
     */
    public static void prependToBody(com.sun.tools.javac.tree.JCTree.JCMethodDecl jcMethod,
                                     com.sun.tools.javac.tree.JCTree.JCStatement... stats) {
        com.sun.tools.javac.tree.JCTree.JCBlock body = jcMethod.body;
        if (body == null) {
            return;
        }

        com.sun.tools.javac.tree.JCTree.JCStatement[] original =
                body.stats.toArray(new com.sun.tools.javac.tree.JCTree.JCStatement[0]);
        com.sun.tools.javac.tree.JCTree.JCStatement[] combined =
                new com.sun.tools.javac.tree.JCTree.JCStatement[original.length + stats.length];
        System.arraycopy(stats, 0, combined, 0, stats.length);
        System.arraycopy(original, 0, combined, stats.length, original.length);
        body.stats = com.sun.tools.javac.util.List.from(combined);
    }

    /**
     * 在方法体的末尾追加语句
     *
     * @param jcMethod 方法声明
     * @param stats    要追加的语句
     */
    public static void appendToBody(com.sun.tools.javac.tree.JCTree.JCMethodDecl jcMethod,
                                    com.sun.tools.javac.tree.JCTree.JCStatement... stats) {
        com.sun.tools.javac.tree.JCTree.JCBlock body = jcMethod.body;
        if (body == null) {
            return;
        }

        com.sun.tools.javac.tree.JCTree.JCStatement[] original =
                body.stats.toArray(new com.sun.tools.javac.tree.JCTree.JCStatement[0]);
        com.sun.tools.javac.tree.JCTree.JCStatement[] combined =
                new com.sun.tools.javac.tree.JCTree.JCStatement[original.length + stats.length];
        System.arraycopy(original, 0, combined, 0, original.length);
        System.arraycopy(stats, 0, combined, original.length, stats.length);
        body.stats = com.sun.tools.javac.util.List.from(combined);
    }

    /**
     * 判断 类型mirror 是否为基本类型或包装类型（含 字符串）
     *
     * @param type 类型镜像
     * @return 如果是基本类型、包装类或 字符串 返回 true，否则返回 false
     */
    public static boolean isPrimitiveOrWrapper(TypeMirror type) {
        if (type == null) {
            return false;
        }
        TypeKind kind = type.getKind();
        if (kind.isPrimitive()) {
            return true;
        }
        String name = type.toString();
        return "java.lang.Boolean".equals(name) ||
                "java.lang.Byte".equals(name) ||
                "java.lang.Short".equals(name) ||
                "java.lang.Integer".equals(name) ||
                "java.lang.Long".equals(name) ||
                "java.lang.Float".equals(name) ||
                "java.lang.Double".equals(name) ||
                "java.lang.Character".equals(name) ||
                "java.lang.String".equals(name);
    }

    /**
     * 判断 类型mirror 是否为 字符串 类型
     *
     * @param type 类型镜像
     * @return 如果是 Java.lang.字符串 类型返回 true，否则返回 false
     */
    public static boolean isString(TypeMirror type) {
        return type != null && "java.lang.String".equals(type.toString());
    }

    /**
     * 创建无参的 新 表达式 {@code new XxxClass()}
     *
     * @param maker     树maker 实例
     * @param names     名称 实例
     * @param className 类名
     * @return new 表达式
     */
    public static com.sun.tools.javac.tree.JCTree.JCNewClass newClass(com.sun.tools.javac.tree.TreeMaker maker,
                                                                      com.sun.tools.javac.util.Names names,
                                                                      String className) {
        com.sun.tools.javac.tree.JCTree.JCIdent classIdent =
                maker.Ident(names.fromString(className));
        return maker.NewClass(null, com.sun.tools.javac.util.List.nil(),
                classIdent, com.sun.tools.javac.util.List.nil(), null);
    }

    /**
     * 创建带参数的 新 表达式 {@code new XxxClass(args)}
     *
     * @param maker     树maker 实例
     * @param names     名称 实例
     * @param className 类名
     * @param args      构造参数列表
     * @return new 表达式
     */
    public static com.sun.tools.javac.tree.JCTree.JCNewClass newClass(com.sun.tools.javac.tree.TreeMaker maker,
                                                                      com.sun.tools.javac.util.Names names,
                                                                      String className,
                                                                      com.sun.tools.javac.util.List<com.sun.tools.javac.tree.JCTree.JCExpression> args) {
        com.sun.tools.javac.tree.JCTree.JCIdent classIdent =
                maker.Ident(names.fromString(className));
        return maker.NewClass(null, com.sun.tools.javac.util.List.nil(),
                classIdent, args, null);
    }

    /**
     * 创建字段访问表达式 {@code owner.fieldName}
     *
     * @param maker     树maker 实例
     * @param names     名称 实例
     * @param owner     所有者名称
     * @param fieldName 字段名称
     * @return 字段访问表达式
     */
    public static com.sun.tools.javac.tree.JCTree.JCFieldAccess fieldAccess(com.sun.tools.javac.tree.TreeMaker maker,
                                                                            com.sun.tools.javac.util.Names names,
                                                                            String owner,
                                                                            String fieldName) {
        return maker.Select(maker.Ident(names.fromString(owner)),
                names.fromString(fieldName));
    }

    /**
     * 创建方法调用表达式 {@code owner.methodName(args...)}
     *
     * @param maker      树maker 实例
     * @param names      名称 实例
     * @param owner      所有者名称
     * @param methodName 方法名称
     * @param args       参数列表
     * @return 方法调用表达式
     */
    @SafeVarargs
    public static com.sun.tools.javac.tree.JCTree.JCMethodInvocation methodCall(com.sun.tools.javac.tree.TreeMaker maker,
                                                                                com.sun.tools.javac.util.Names names,
                                                                                String owner,
                                                                                String methodName,
                                                                                com.sun.tools.javac.tree.JCTree.JCExpression... args) {
        com.sun.tools.javac.tree.JCTree.JCFieldAccess select =
                maker.Select(maker.Ident(names.fromString(owner)),
                        names.fromString(methodName));
        return maker.Apply(com.sun.tools.javac.util.List.nil(), select,
                com.sun.tools.javac.util.List.from(args));
    }

    /**
     * 创建 选择 访问表达式 {@code owner.name}
     *
     * @param maker 树maker 实例
     * @param names 名称 实例
     * @param owner 所有者名称
     * @param name  名称
     * @return 字段访问表达式
     */
    public static com.sun.tools.javac.tree.JCTree.JCFieldAccess select(com.sun.tools.javac.tree.TreeMaker maker,
                                                                       com.sun.tools.javac.util.Names names,
                                                                       String owner,
                                                                       String name) {
        return maker.Select(maker.Ident(names.fromString(owner)),
                names.fromString(name));
    }

    // ========================================================================
    // 向后兼容的废弃方法
    // ========================================================================

    /**
     * @deprecated 已废弃，请使用 {@link #asJcMethod(Element, ProcessingEnvironment, Messager)}
     * @param methodTree 方法树
     * @return asjc方法decl的结果
     */
    @Deprecated
    public static com.sun.tools.javac.tree.JCTree.JCMethodDecl asJcMethodDecl(com.sun.source.tree.Tree methodTree) {
        if (methodTree instanceof com.sun.tools.javac.tree.JCTree.JCMethodDecl jc) {
            return jc;
        }
        return null;
    }

    /**
     * @deprecated 已废弃，请使用 {@link #treeMaker(ProcessingEnvironment)}
     * @param env env
     * @return 获取树maker的结果
     */
    @Deprecated
    public static com.sun.tools.javac.tree.TreeMaker getTreeMaker(ProcessingEnvironment env) {
        return treeMaker(env);
    }

    /**
     * @deprecated 已废弃，请使用 {@link #names(ProcessingEnvironment)}
     * @param env env
     * @return 获取名称的结果
     */
    @Deprecated
    public static com.sun.tools.javac.util.Names getNames(ProcessingEnvironment env) {
        return names(env);
    }

    /**
     * @deprecated 已废弃，请使用 {@link #ident(TreeMaker, Names, String)}
     */
    @Deprecated
    public static com.sun.tools.javac.tree.JCTree.JCIdent makeIdent(com.sun.tools.javac.tree.TreeMaker maker,
                                                                    com.sun.tools.javac.util.Names names,
                                                                    String name) {
        return ident(maker, names, name);
    }

    /**
     * @deprecated 已废弃，请使用 {@link #nullLiteral(TreeMaker)}
     * @param maker maker
     * @return make空字面量的结果
     */
    @Deprecated
    public static com.sun.tools.javac.tree.JCTree.JCLiteral makeNullLiteral(com.sun.tools.javac.tree.TreeMaker maker) {
        return nullLiteral(maker);
    }

    /**
     * @deprecated 已废弃，请使用 {@link #eq(TreeMaker, JCExpression, JCExpression)}
     */
    @Deprecated
    public static com.sun.tools.javac.tree.JCTree.JCBinary makeEq(com.sun.tools.javac.tree.TreeMaker maker,
                                                                  com.sun.tools.javac.tree.JCTree.JCExpression left,
                                                                  com.sun.tools.javac.tree.JCTree.JCExpression right) {
        return eq(maker, left, right);
    }

    /**
     * 创建 AST 字面量表达式，根据参数类型自动选择对应的 类型标签
     *
     * <p>该方法用于 {@link com.chua.ast.support.annotation.DefaultValue @DefaultValue} 注解的编译期处理，
     * 根据参数的类型生成对应的字面量 AST 节点。</p>
     *
     * @param maker        树maker 实例
     * @param names        名称 实例
     * @param defaultValue 默认值字符串
     * @param paramType    参数类型镜像
     * @return 字面量 AST 表达式
     */
    public static com.sun.tools.javac.tree.JCTree.JCExpression makeLiteral(com.sun.tools.javac.tree.TreeMaker maker,
                                                                           com.sun.tools.javac.util.Names names,
                                                                           String defaultValue,
                                                                           javax.lang.model.type.TypeMirror paramType) {
        javax.lang.model.type.TypeKind kind = paramType.getKind();
        return switch (kind) {
            case BOOLEAN -> maker.Literal(com.sun.tools.javac.code.TypeTag.BOOLEAN, Boolean.parseBoolean(defaultValue));
            case BYTE -> maker.Literal(com.sun.tools.javac.code.TypeTag.BYTE, Byte.parseByte(defaultValue));
            case SHORT -> maker.Literal(com.sun.tools.javac.code.TypeTag.SHORT, Short.parseShort(defaultValue));
            case INT -> maker.Literal(com.sun.tools.javac.code.TypeTag.INT, Integer.parseInt(defaultValue));
            case LONG -> maker.Literal(com.sun.tools.javac.code.TypeTag.LONG, Long.parseLong(defaultValue));
            case FLOAT -> maker.Literal(com.sun.tools.javac.code.TypeTag.FLOAT, Float.parseFloat(defaultValue));
            case DOUBLE -> maker.Literal(com.sun.tools.javac.code.TypeTag.DOUBLE, Double.parseDouble(defaultValue));
            case CHAR -> maker.Literal(com.sun.tools.javac.code.TypeTag.CHAR, defaultValue.charAt(0));
            default -> maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS, defaultValue);
        };
    }

    /**
     * @deprecated 已废弃，请使用 {@link #makeLiteral(TreeMaker, Names, String, TypeMirror)}
     */
    @Deprecated
    public static com.sun.tools.javac.tree.JCTree.JCExpression makeLiteral(com.sun.tools.javac.tree.TreeMaker maker,
                                                                           String defaultValue,
                                                                           javax.lang.model.type.TypeMirror paramType) {
        return makeLiteral(maker, null, defaultValue, paramType);
    }

    /**
     * @deprecated 已废弃，请使用 {@link #assign(TreeMaker, JCExpression, JCExpression)} + exec
     */
    @Deprecated
    public static com.sun.tools.javac.tree.JCTree.JCExpressionStatement makeAssign(com.sun.tools.javac.tree.TreeMaker maker,
                                                                                   com.sun.tools.javac.tree.JCTree.JCExpression lhs,
                                                                                   com.sun.tools.javac.tree.JCTree.JCExpression rhs) {
        return maker.Exec(assign(maker, lhs, rhs));
    }

    /**
     * @deprecated 已废弃，请使用 {@link #prependToBody(JCMethodDecl, JCStatement...)}
     */
    @Deprecated
    public static void prependToMethodBody(com.sun.tools.javac.tree.JCTree.JCMethodDecl jcMethod,
                                           com.sun.tools.javac.tree.JCTree.JCStatement stat) {
        prependToBody(jcMethod, stat);
    }

    // ========================================================================
 // jc字面量 工具方法
    // ========================================================================

    /**
     * 创建 jc字面量 字面量，根据值的类型自动选择对应的 类型标签
     *
     * @param maker 树maker 实例
     * @param value 字面量值
     * @return JCLiteral 实例
     */
    public static com.sun.tools.javac.tree.JCTree.JCLiteral literal(com.sun.tools.javac.tree.TreeMaker maker, Object value) {
        if (value instanceof Boolean) {
            return maker.Literal(com.sun.tools.javac.code.TypeTag.BOOLEAN, value);
        } else if (value instanceof Integer) {
            return maker.Literal(com.sun.tools.javac.code.TypeTag.INT, value);
        } else if (value instanceof Long) {
            return maker.Literal(com.sun.tools.javac.code.TypeTag.LONG, value);
        } else if (value instanceof Float) {
            return maker.Literal(com.sun.tools.javac.code.TypeTag.FLOAT, value);
        } else if (value instanceof Double) {
            return maker.Literal(com.sun.tools.javac.code.TypeTag.DOUBLE, value);
        } else if (value instanceof Character) {
            return maker.Literal(com.sun.tools.javac.code.TypeTag.CHAR, value);
        } else if (value instanceof String) {
            return maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS, value);
        }
 // 空
        return maker.Literal(com.sun.tools.javac.code.TypeTag.BOT, null);
    }
}
