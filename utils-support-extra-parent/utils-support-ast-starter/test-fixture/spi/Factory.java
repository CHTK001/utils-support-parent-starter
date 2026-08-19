package com.chua.test.spi;

/**
 * @author CH
 * Test fixture with a nested interface (binary name com.chua.test.spi.Factory$Handler).
 */
public interface Factory {

    /**
     * Nested handler interface.
     */
    interface Handler {

        /**
         * Handle.
         */
        void handle();
    }
}

