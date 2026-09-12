package com.chua.test.spi;

/**
 * @author CH
   * 测试 fixture with a 嵌套 接口 (binary 名称 com.chua.测试.spi.工厂$处理器).
 * @since 4.0.0
 */
public interface Factory {

    /**
      * 嵌套 处理器 接口.
     * @author CH
     * @since 4.0.0
     */
    interface Handler {

        /**
          * 处理.
         */
        void handle();
    }
}

