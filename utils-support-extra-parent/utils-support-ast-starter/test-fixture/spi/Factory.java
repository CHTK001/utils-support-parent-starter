package com.chua.test.spi;

/**
 * Host type holding a nested SPI interface (tests binary-name $ file generation).
 */
public class Factory {

    /**
     * Nested SPI interface.
     */
    public interface Handler {
        void handle();
    }
}
