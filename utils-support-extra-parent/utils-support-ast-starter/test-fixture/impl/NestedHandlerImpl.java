package com.chua.test.impl;

import com.chua.ast.support.annotation.SpiExtension;
import com.chua.test.spi.Factory;

/**
 * Implements a nested interface; generated file must use binary name com.chua.test.spi.Factory$Handler.
 */
@SpiExtension(name = "nested")
public class NestedHandlerImpl implements Factory.Handler {

    @Override
    public void handle() {
    }
}
