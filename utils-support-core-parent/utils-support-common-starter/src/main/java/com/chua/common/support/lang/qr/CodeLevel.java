package com.chua.common.support.lang.qr;


/**
* 二维码纠错等级枚举。
* <p>
* 定义了QR码的四种标准纠错级别，用于平衡数据容量与错误恢复能力。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public enum CodeLevel {
    /**
    * 低纠错等级 (Low)，可恢复约7%的数据错误。
    */
    L,

    /**
    * 中等纠错等级 (Medium)，可恢复约15%的数据错误。
    */
    M,

    /**
    * 高纠错等级 (Quartile)，可恢复约25%的数据错误。
    */
    Q,

    /**
    * 最高纠错等级 (High)，可恢复约30%的数据错误。
    */
    H;
}
