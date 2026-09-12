package com.chua.ast.support.processor;

import javax.annotation.processing.ProcessingEnvironment;


import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.lang.reflect.Field;

/**
 * AST 工具类，封装 javac 编译树 API 的常用操作
 *
 * <p>提供从 {@code com.sun.tools.javac.tree.TreeMaker} 和 {@code com.sun.tools.javac.util.Names}
 * 等 javac 内部 API 的便捷访问方法，简化 AST 处理器的开发。</p>
 *
 * @author CH
 * @since 2024
 */
public final class AstUtils {

    /** 创建 ast工具 实例 */
    private AstUtils() {}

    /**
      * 从编译处理环境中获取 javac 上下文 实例
     *
     * <p>
      * 优先尝试从 javac处理环境 获取（适用于 JDK 25+），
      * 失败时降级从 javac树 反射获取。
     * </p>
     *
     * @param processingEnv 编译处理环境
     * @return javac 上下文 实例
     */
    public static com.sun.tools.javac.util.Context getContext(ProcessingEnvironment processingEnv) {
        // 方式 1：从 JavacProcessingEnvironment 获取 Context（JDK 25+ 支持 getContext() 方法）
        try {
            Class<?> javacEnvClass = Class.forName("com.sun.tools.javac.processing.JavacProcessingEnvironment");
            if (javacEnvClass.isInstance(processingEnv)) {
                try {
                    java.lang.reflect.Method getContextMethod = javacEnvClass.getMethod("getContext");
                    return (com.sun.tools.javac.util.Context) getContextMethod.invoke(processingEnv);
                } catch (NoSuchMethodException nsme) {
                    // JDK 25 以下版本没有 getContext() 方法，尝试直接反射 context 字段
                    try {
                        Field contextField = javacEnvClass.getDeclaredField("context");
                        contextField.setAccessible(true);
                        return (com.sun.tools.javac.util.Context) contextField.get(processingEnv);
                    } catch (NoSuchFieldException nsfe) {
                        // 方式 1 失败，继续尝试方式 2
                    }
                }
            }
        } catch (Exception e) {
            // 方式 1 失败，继续尝试方式 2
        }

 // 方式 2：从 javac树 反射获取 上下文
        try {
            com.sun.tools.javac.api.JavacTrees javacTrees =
                    (com.sun.tools.javac.api.JavacTrees) com.sun.source.util.Trees.instance(processingEnv);
            for (String fieldName : new String[]{"context", "treeContext"}) {
                try {
                    Field contextField = com.sun.tools.javac.api.JavacTrees.class.getDeclaredField(fieldName);
                    contextField.setAccessible(true);
                    return (com.sun.tools.javac.util.Context) contextField.get(javacTrees);
                } catch (NoSuchFieldException nsfe) {
                    // 字段名不匹配，继续尝试下一个
                }
            }
        } catch (Exception e) {
            // 所有方式均失败
        }

        throw new RuntimeException("无法获取 javac Context 实例");
    }

    /**
      * 获取 树maker 实例
     *
     * @param processingEnv 编译处理环境
     * @return TreeMaker 实例
     */
    public static com.sun.tools.javac.tree.TreeMaker getTreeMaker(ProcessingEnvironment processingEnv) {
        return com.sun.tools.javac.tree.TreeMaker.instance(getContext(processingEnv));
    }

    /**
      * 获取 名称 实例
     *
     * @param processingEnv 编译处理环境
     * @return Names 实例
     */
    public static com.sun.tools.javac.util.Names getNames(ProcessingEnvironment processingEnv) {
        return com.sun.tools.javac.util.Names.instance(getContext(processingEnv));
    }

    /**
      * 将 源 树 转换为 javac 内部的 jc方法decl
     *
     * @param methodTree 方法树节点
     * @return JCMethodDecl 实例，转换失败返回 空
     */
    public static com.sun.tools.javac.tree.JCTree.JCMethodDecl asJcMethodDecl(com.sun.source.tree.Tree methodTree) {
        if (methodTree instanceof com.sun.tools.javac.tree.JCTree.JCMethodDecl jc) {
            return jc;
        }
        return null;
    }

    /**
     * 创建标识符表达式：{@code paramName}
     *
     * @param maker 树maker 实例
     * @param names 名称 实例
     * @param paramName 参数名称
     * @return 标识符表达式
     */
    public static com.sun.tools.javac.tree.JCTree.JCIdent makeIdent(com.sun.tools.javac.tree.TreeMaker maker,
            com.sun.tools.javac.util.Names names,
            String paramName) {
        return maker.Ident(names.fromString(paramName));
    }

    /**
      * 创建 空 字面量
     *
     * @param maker 树maker 实例
     * @return null 字面量
     */
    public static com.sun.tools.javac.tree.JCTree.JCLiteral makeNullLiteral(com.sun.tools.javac.tree.TreeMaker maker) {
        return maker.Literal(com.sun.tools.javac.code.TypeTag.BOT, null);
    }

    /**
     * 创建相等比较表达式：{@code left == right}
     *
     * @param maker 树maker 实例
     * @param left 左操作数
     * @param right 右操作数
     * @return 相等比较表达式
     */
    public static com.sun.tools.javac.tree.JCTree.JCBinary makeEq(com.sun.tools.javac.tree.TreeMaker maker,
            com.sun.tools.javac.tree.JCTree.JCExpression left,
            com.sun.tools.javac.tree.JCTree.JCExpression right) {
        return maker.Binary(com.sun.tools.javac.tree.JCTree.Tag.EQ, left, right);
    }

    /**
      * 创建 AST 字面量表达式，根据类型自动选择对应的 类型标签
     *
     * @param maker 树maker 实例
     * @param value 字面量值字符串
     * @param type 类型镜像
     * @return 字面量 AST 表达式
     */
    public static com.sun.tools.javac.tree.JCTree.JCExpression makeLiteral(com.sun.tools.javac.tree.TreeMaker maker,
            String value,
            TypeMirror type) {
        TypeKind kind = type.getKind();
        return switch (kind) {
            case BOOLEAN -> maker.Literal(com.sun.tools.javac.code.TypeTag.BOOLEAN, Boolean.parseBoolean(value) ? 1 : 0);
            case BYTE -> maker.Literal(com.sun.tools.javac.code.TypeTag.BYTE, Byte.parseByte(stripSuffix(value)));
            case SHORT -> maker.Literal(com.sun.tools.javac.code.TypeTag.SHORT, Short.parseShort(stripSuffix(value)));
            case INT -> maker.Literal(com.sun.tools.javac.code.TypeTag.INT, Integer.parseInt(stripSuffix(value)));
            case LONG -> maker.Literal(com.sun.tools.javac.code.TypeTag.LONG, Long.parseLong(stripSuffix(value)));
            case FLOAT -> maker.Literal(com.sun.tools.javac.code.TypeTag.FLOAT, Float.parseFloat(stripSuffix(value)));
            case DOUBLE -> maker.Literal(com.sun.tools.javac.code.TypeTag.DOUBLE, Double.parseDouble(stripSuffix(value)));
            case CHAR -> maker.Literal(com.sun.tools.javac.code.TypeTag.CHAR, value.charAt(0));
            default -> {
                String typeStr = type.toString();
                if ("java.lang.String".equals(typeStr) || "java.lang.CharSequence".equals(typeStr)) {
                    yield maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS, value);
                }
                yield maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS, value);
            }
        };
    }

    /**
     * 去除数值后缀（如 100L -> 100, 3.14f -> 3.14）
     * @param value 值
     * @return strip后缀的结果
     */
    private static String stripSuffix(String value) {
        if (value == null || value.isEmpty()) { return value; }
        char last = value.charAt(value.length() - 1);
        if (last == 'L' || last == 'l' || last == 'F' || last == 'f' || last == 'D' || last == 'd') {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 创建赋值语句：{@code ident = expression;}
     *
     * @param maker 树maker 实例
     * @param lhs 左值标识符
     * @param rhs 右值表达式
     * @return 赋值表达式语句
     */
    public static com.sun.tools.javac.tree.JCTree.JCExpressionStatement makeAssign(com.sun.tools.javac.tree.TreeMaker maker,
            com.sun.tools.javac.tree.JCTree.JCExpression lhs,
            com.sun.tools.javac.tree.JCTree.JCExpression rhs) {
        return maker.Exec(maker.Assign(lhs, rhs));
    }

    /**
     * 在方法体开头插入语句
     *
     * @param jcMethod 方法声明
     * @param stmt 要插入的语句
     */
    public static void prependToMethodBody(com.sun.tools.javac.tree.JCTree.JCMethodDecl jcMethod,
            com.sun.tools.javac.tree.JCTree.JCStatement stmt) {
        if (jcMethod.body == null) { return; }

        com.sun.tools.javac.tree.JCTree.JCStatement[] original =
                jcMethod.body.stats.toArray(new com.sun.tools.javac.tree.JCTree.JCStatement[0]);
        com.sun.tools.javac.tree.JCTree.JCStatement[] result =
                new com.sun.tools.javac.tree.JCTree.JCStatement[original.length + 1];
        result[0] = stmt;
        System.arraycopy(original, 0, result, 1, original.length);
        jcMethod.body.stats = com.sun.tools.javac.util.List.from(result);
    }

    /**
     * 在方法体末尾追加语句
     *
     * @param jcMethod 方法声明
     * @param stmt 要追加的语句
     */
    public static void appendToMethodBody(com.sun.tools.javac.tree.JCTree.JCMethodDecl jcMethod,
            com.sun.tools.javac.tree.JCTree.JCStatement stmt) {
        if (jcMethod.body == null) { return; }

        com.sun.tools.javac.tree.JCTree.JCStatement[] original =
                jcMethod.body.stats.toArray(new com.sun.tools.javac.tree.JCTree.JCStatement[0]);
        com.sun.tools.javac.tree.JCTree.JCStatement[] result =
                new com.sun.tools.javac.tree.JCTree.JCStatement[original.length + 1];
        System.arraycopy(original, 0, result, 0, original.length);
        result[result.length - 1] = stmt;
        jcMethod.body.stats = com.sun.tools.javac.util.List.from(result);
    }

    /**
      * 判断 类型mirror 是否为 字符串 类型
     *
     * @param type 类型镜像
     * @return 如果是 字符串 类型返回 true，否则返回 false
     */
    public static boolean isStringType(TypeMirror type) {
        String ts = type.toString();
        return "java.lang.String".equals(ts) || "java.lang.CharSequence".equals(ts);
    }

    /**
      * 判断 类型mirror 是否为基本类型或对应的包装类型
     *
     * @param type 类型镜像
     * @return 如果是基本类型或包装类型返回 true，否则返回 false
     */
    public static boolean isPrimitiveOrWrapper(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE -> true;
            default -> {
                String ts = type.toString();
                yield "java.lang.Boolean".equals(ts)
                        || "java.lang.Byte".equals(ts)
                        || "java.lang.Short".equals(ts)
                        || "java.lang.Integer".equals(ts)
                        || "java.lang.Long".equals(ts)
                        || "java.lang.Float".equals(ts)
                        || "java.lang.Double".equals(ts)
                        || "java.lang.Character".equals(ts);
            }
        };
    }

    /**
      * 判断 类型mirror 是否为引用类型
     *
     * @param type 类型镜像
     * @return 如果是引用类型返回 true，否则返回 false
     */
    public static boolean isReferenceType(TypeMirror type) {
        return !type.getKind().isPrimitive();
    }

    /**
      * 创建 转为字符串 方法：{@code public String toString() { ... }}
     *
     * @param maker 树maker 实例
     * @param names 名称 实例
     * @param bodyStatement 方法体语句
     * @return toString 方法声明
     */
    public static com.sun.tools.javac.tree.JCTree.JCMethodDecl createToStringMethod(com.sun.tools.javac.tree.TreeMaker maker,
            com.sun.tools.javac.util.Names names,
            com.sun.tools.javac.tree.JCTree.JCStatement bodyStatement) {

 // 返回类型：字符串
        com.sun.tools.javac.tree.JCTree.JCExpression returnType =
                maker.Ident(names.fromString("String"));

        // 方法体
        com.sun.tools.javac.tree.JCTree.JCBlock body =
                maker.Block(0, com.sun.tools.javac.util.List.of(bodyStatement));

 // 公共 访问修饰符
        long mods = com.sun.tools.javac.code.Flags.PUBLIC;

 // 创建 转为字符串 方法
        return maker.MethodDef(maker.Modifiers(mods),
                names.fromString("toString"),
                returnType,
                // 类型参数
                com.sun.tools.javac.util.List.nil(),
                // 参数列表
                com.sun.tools.javac.util.List.nil(),
                // 异常列表
                com.sun.tools.javac.util.List.nil(),
                body,
 // 默认值
                null);
    }


    /**
      * 创建 返回 语句：{@code return expr;}
     *
     * @param maker 树maker 实例
     * @param expr 返回表达式
     * @return return 语句
     */
    public static com.sun.tools.javac.tree.JCTree.JCReturn makeReturn(com.sun.tools.javac.tree.TreeMaker maker,
            com.sun.tools.javac.tree.JCTree.JCExpression expr) {
        return maker.Return(expr);
    }

    /**
      * 获取方法声明所属的 jc类decl 类声明节点
     *
     * @param jcMethod 方法声明
     * @return 所属的类声明节点
     */
    public static com.sun.tools.javac.tree.JCTree.JCClassDecl getEnclosingClassDecl(com.sun.tools.javac.tree.JCTree.JCMethodDecl jcMethod) {
        com.sun.tools.javac.tree.JCTree parent = jcMethod;
        while (parent != null) {
            if (parent instanceof com.sun.tools.javac.tree.JCTree.JCClassDecl classDecl) {
                return classDecl;
            }
 // 通过 父 字段向上遍历 AST 树
            try {
                Field parentField = com.sun.tools.javac.tree.JCTree.class.getDeclaredField("parent");
                parentField.setAccessible(true);
                parent = (com.sun.tools.javac.tree.JCTree) parentField.get(parent);
            } catch (Exception e) {
                break;
            }
        }
        return null;
    }

    /**
     * 向类声明中添加成员
     *
     * @param classDecl 类声明
     * @param member 要添加的成员
     */
    public static void addClassMember(com.sun.tools.javac.tree.JCTree.JCClassDecl classDecl,
            com.sun.tools.javac.tree.JCTree member) {

        com.sun.tools.javac.tree.JCTree[] original =
                classDecl.defs.toArray(new com.sun.tools.javac.tree.JCTree[0]);
        com.sun.tools.javac.tree.JCTree[] result =
                new com.sun.tools.javac.tree.JCTree[original.length + 1];
        System.arraycopy(original, 0, result, 0, original.length);
        result[result.length - 1] = member;
        classDecl.defs = com.sun.tools.javac.util.List.from(result);
    }

    /**
     * 判断类声明中是否包含指定名称的字段
     *
     * @param classDecl 类声明
     * @param fieldName 字段名称
     * @return 如果存在该字段返回 true，否则返回 false
     */
    public static boolean hasField(com.sun.tools.javac.tree.JCTree.JCClassDecl classDecl,
            String fieldName) {
        for (com.sun.tools.javac.tree.JCTree def : classDecl.defs) {
            if (def instanceof com.sun.tools.javac.tree.JCTree.JCVariableDecl var) {
                if (var.name.toString().equals(fieldName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
