package com.chua.common.support.file.system;


/**
* 文件读取选项，控制文件读取时的行为参数。
*
* <p>支持设置返回格式（Map/对象）、起始行号、读取行数限制等参数。</p>
*
* @author CH
* @since 1.0.0
 */
public class ReadOption {

    /** 是否以 Map 格式返回数据 */
    private boolean asMap;

    /** 起始读取行号（从 0 开始） */
    private int startRow;

    /** 最大读取行数，0 表示不限制 */
    /**
    * 限制
    */
    private int limit;

    /**
    * 创建以 Map 格式返回的读取选项。
    *
    * @return 读取选项
    */
    public static ReadOption maps() {
        ReadOption option = new ReadOption();
        option.asMap = true;
        return option;
    }

    /**
    * 创建默认读取选项。
    *
    * @return 读取选项
    */
    public static ReadOption of() {
        return new ReadOption();
    }

    /**
     * 是否AsMap
     * @return 是否成功（true 表示成功）
     */
    public boolean isAsMap() {
        return asMap;
    }

    /**
     * 获取开始Row
     * @return 结果数值
     */
    public int getStartRow() {
        return startRow;
    }

    /**
    * 设置起始行号。
    *
    * @param startRow 起始行号
    * @return 当前选项
    */
    public ReadOption startRow(int startRow) {
        this.startRow = startRow;
        return this;
    }

    /**
     * 获取Limit
     * @return 结果数值
     */
    public int getLimit() {
        return limit;
    }

    /**
    * 设置最大读取行数。
    *
    * @param limit 最大行数，0 为不限制
    * @return 当前选项
    */
    public ReadOption limit(int limit) {
        this.limit = limit;
        return this;
    }
}
