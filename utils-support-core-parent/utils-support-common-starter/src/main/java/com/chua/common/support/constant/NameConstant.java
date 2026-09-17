package com.chua.common.support.constant;


/**
* 名称常量接口，定义了常用的字符串常量。
*
* <p>该接口包含了一系列常用的字符串常量，这些常量在项目中被广泛用于：
* <ul>
*   <li>方法命名约定（getter/setter）</li>
*   <li>状态标识（开/关、是/否）</li>
*   <li>字段名称（id、name、value 等）</li>
*   <li>响应结构（code、message、data 等）</li>
*   <li>编码格式（UTF-8）</li>
* </ul>
*
* <p>使用这些常量可以：</p>
* <ul>
*   <li>避免魔法字符串（magic strings）</li>
*   <li>提高代码的可维护性和可读性</li>
*   <li>减少拼写错误</li>
*   <li>支持重构时的全局替换</li>
* </ul>
*
* @author CH
* @since 1.0
 */
public final class NameConstant {
    private NameConstant() {}

    /**
    * JPEG 图片格式名称 {@value}。
    */
    public static final String JPEG = "jpeg";
    /**
    * 默认值字符串 "default"。
    * 常用于表示默认配置、默认实例等场景。
    */
    public static final String DEFAULT = "default";

    /**
    * getter 方法前缀 "get"。
    * 常用于反射获取方法名、JavaBean 属性访问等场景。
    */
    public static final String METHOD_GETTER = "get";

    /**
    * setter 方法前缀 "set"。
    * 常用于反射设置方法名、JavaBean 属性访问等场景。
    */
    public static final String METHOD_SETTER = "set";

    /**
    * null 字符串 "null"。
    * 常用于字符串比较、JSON 处理等场景。
    */
    public static final String NULL = "null";

    /**
    * 类型字段 "type"。
    * 常用于分类、类型标识、JSON 字段等场景。
    */
    public static final String TYPE = "type";

    /**
    * UTF-8 编码大写 "UTF-8"。
    * 常用于字符集转换、IO 操作等场景。
    */
    public static final String UTF_8 = "UTF-8";

    /**
    * 类路径 URL 前缀 "classpath:"。
    * 常用于资源加载、类路径扫描等场景。
    */
    public static final String CLASSPATH_URL_PREFIX = "classpath:";

    /**
    * 类路径 URL 前缀 "classpath*:"。
    * 常用于资源加载、类路径扫描等场景。
    */
    public static final String CLASSPATH_URL_ALL_PREFIX = "classpath*:";

    /**
    * 文件系统 URL 前缀 "filesystem:"。
    * 常用于文件路径、文件系统操作等场景。
    */
    public static final String FILE_SYSTEM_URL_PREFIX = "filesystem:";


    /**
    * 文件系统 URL 前缀 "filesystem*:"。
    * 常用于文件路径、文件系统操作等场景。
    */
    public static final String FILE_SYSTEM_URL_ALL_PREFIX = "filesystem*:";
    /**
    * 文件 URL 前缀 "file:"。
    * 常用于文件路径、文件系统操作等场景。
    */
    public static final String FILE_URL_PREFIX = "file:";

    /**
    * FTP URL 前缀 "ftp:"。
    * 常用于文件路径、文件系统操作等场景。
    */
    public static final String FTP_URL_PREFIX = "ftp:";


    // ==================== 操作方法名（大写，用于方法名匹配） ====================

    /** 新增 */
    public static final String SAVE = "SAVE";
    public static final String INSERT = "INSERT";
    public static final String ADD = "ADD";
    /** 修改 */
    public static final String UPDATE = "UPDATE";
    public static final String MODIFY = "MODIFY";
    /** 删除 */
    public static final String DELETE = "DELETE";
    public static final String DROP = "DROP";
    public static final String REMOVE = "REMOVE";
    /** 重置 */
    public static final String RESET = "RESET";

    // ==================== HTTP 方法名 ====================

    /** HTTP GET */
    public static final String GET = "GET";
    /** HTTP POST */
    public static final String POST = "POST";
    /** HTTP PUT */
    public static final String PUT = "PUT";
    /** HTTP DELETE */
    public static final String DELETE_METHOD = "DELETE";
    /** HTTP PATCH */
    public static final String PATCH = "PATCH";
    /** HTTP HEAD */
    public static final String HEAD = "HEAD";

}
