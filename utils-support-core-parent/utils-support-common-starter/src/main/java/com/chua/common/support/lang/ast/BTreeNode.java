package com.chua.common.support.lang.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * B-Tree 节点
 *
 * <p>AST（抽象语法树）的核心节点，表示表达式中的一个操作或值。
 * 每个节点包含类型、值、运算符和子节点，构成二叉树结构。
 *
 * <h3>节点类型</h3>
 * <ul>
 *   <li>OPERATOR — 运算符节点（AND/OR/NOT/比较运算符），有左子节点</li>
 *   <li>LOGIC — 逻辑运算符（AND/OR），有左右子节点</li>
 *   <li>NOT — 逻辑非，只有右子节点</li>
 *   <li>COLUMN — 列引用（如 age、status），叶子节点</li>
 *   <li>VALUE — 常量值（如 18、'active'），叶子节点</li>
 *   <li>FUNCTION — 函数调用（如 NOW()、UPPER(name)）</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/16
 */
public class BTreeNode {

    /**
     * 节点类型
     */
    public enum Type {

        /** 逻辑运算：AND / OR */
        LOGIC,

        /** 逻辑非：NOT */
        NOT,

        /** 比较运算：= / != / > / < / >= / <= */
        COMPARE,

        /** 列引用：如 age、status */
        COLUMN,

        /** 常量值：如 18、'active'、TRUE */
        VALUE,

        /** 函数调用：如 NOW()、UPPER(name) */
        FUNCTION,

        /** 原始表达式（未解析的文本） */
        RAW
    }

    /** 节点类型 */
    /**
     * 类型
     */
    private final Type type;

    /** 运算符或列名或函数名 */
    private final String operator;

    /** 值（VALUE 类型时为实际值，其他类型可能为 null） */
    /**
     * 值
     */
    private final Object value;

    /** 左子节点（LOGIC/COMPARE/FUNCTION 时可能有值） */
    private BTreeNode left;

    /** 右子节点（LOGIC/NOT/COMPARE 时有值） */
    private BTreeNode right;

    /** 子节点列表（FUNCTION 参数等） */
    private final List<BTreeNode> children = new ArrayList<>();

    public BTreeNode(Type type, String operator, Object value) {
        this.type = type;
        this.operator = operator;
        this.value = value;
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 创建逻辑运算节点
     *
     * @param operator "AND" 或 "OR"
     * @param left     左子节点
     * @param right    右子节点
     * @return 逻辑运算节点
     */
    public static BTreeNode logic(String operator, BTreeNode left, BTreeNode right) {
        BTreeNode node = new BTreeNode(Type.LOGIC, operator, null);
        node.left = left;
        node.right = right;
        return node;
    }

    /**
     * 创建 AND 节点
     */
    public static BTreeNode and(BTreeNode left, BTreeNode right) {
        return logic("AND", left, right);
    }

    /**
     * 创建 OR 节点
     */
    public static BTreeNode or(BTreeNode left, BTreeNode right) {
        return logic("OR", left, right);
    }

    /**
     * 创建 NOT 节点
     *
     * @param child 子节点
     * @return NOT 节点
     */
    public static BTreeNode not(BTreeNode child) {
        BTreeNode node = new BTreeNode(Type.NOT, "NOT", null);
        node.right = child;
        return node;
    }

    /**
     * 创建比较运算节点
     *
     * @param operator 比较运算符（=, !=, >, <, >=, <=, LIKE, IN）
     * @param left     左操作数（通常是列引用）
     * @param right    右操作数（通常是值）
     * @return 比较运算节点
     */
    public static BTreeNode compare(String operator, BTreeNode left, BTreeNode right) {
        BTreeNode node = new BTreeNode(Type.COMPARE, operator, null);
        node.left = left;
        node.right = right;
        return node;
    }

    /**
     * 创建列引用节点
     *
     * @param columnName 列名
     * @return 列引用节点
     */
    public static BTreeNode column(String columnName) {
        return new BTreeNode(Type.COLUMN, columnName, null);
    }

    /**
     * 创建常量值节点
     *
     * @param value 常量值
     * @return 常量值节点
     */
    public static BTreeNode value(Object value) {
        return new BTreeNode(Type.VALUE, null, value);
    }

    /**
     * 创建函数调用节点
     *
     * @param functionName 函数名
     * @param args         函数参数
     * @return 函数节点
     */
    public static BTreeNode function(String functionName, BTreeNode... args) {
        BTreeNode node = new BTreeNode(Type.FUNCTION, functionName, null);
        for (BTreeNode arg : args) {
            node.children.add(arg);
        }
        return node;
    }

    /**
     * 创建原始表达式节点（未解析）
     *
     * @param rawExpression 原始文本
     * @return 原始表达式节点
     */
    public static BTreeNode raw(String rawExpression) {
        return new BTreeNode(Type.RAW, rawExpression, null);
    }

    // ==================== 属性方法 ====================

    public Type getType() {
        return type;
    }

    public String getOperator() {
        return operator;
    }

    public Object getValue() {
        return value;
    }

    public BTreeNode getLeft() {
        return left;
    }

    public BTreeNode getRight() {
        return right;
    }

    public List<BTreeNode> getChildren() {
        return children;
    }

    /**
     * 是否为叶子节点（无子节点）
     */
    public boolean isLeaf() {
        return type == Type.COLUMN || type == Type.VALUE || type == Type.RAW;
    }

    /**
     * 是否为逻辑运算节点
     */
    public boolean isLogic() {
        return type == Type.LOGIC;
    }

    /**
     * 是否为 AND 节点
     */
    public boolean isAnd() {
        return type == Type.LOGIC && "AND".equalsIgnoreCase(operator);
    }

    /**
     * 是否为 OR 节点
     */
    public boolean isOr() {
        return type == Type.LOGIC && "OR".equalsIgnoreCase(operator);
    }

    /**
     * 获取字符串值
     */
    public String asString() {
        return value != null ? String.valueOf(value) : operator;
    }

    /**
     * 获取整数值
     */
    public Integer asInteger() {
        if (value instanceof Number n) { return n.intValue(); }
        if (value instanceof String s) { return Integer.parseInt(s); }
        return null;
    }

    /**
     * 获取双精度值
     */
    public Double asDouble() {
        if (value instanceof Number n) { return n.doubleValue(); }
        if (value instanceof String s) { return Double.parseDouble(s); }
        return null;
    }

    @Override
    public String toString() {
        return switch (type) {
            case LOGIC -> "(" + left + " " + operator + " " + right + ")";
            case NOT -> "(NOT " + right + ")";
            case COMPARE -> "(" + left + " " + operator + " " + right + ")";
            case COLUMN -> operator;
            case VALUE -> {
                if (value instanceof String s) {
                    yield "'" + s + "'";
                }
                if (value instanceof Integer i) {
                    yield String.valueOf(i);
                }
                if (value instanceof Long l) {
                    yield String.valueOf(l);
                }
                if (value instanceof Boolean b) {
                    yield String.valueOf(b);
                }
                yield value == null ? "NULL" : String.valueOf(value);
            }
            case FUNCTION -> operator + "(" + children + ")";
            case RAW -> operator;
        };
    }
}
