package com.chua.vector.support;

import com.chua.vector.support.spi.VectorStorageProviderFactory;

public class MinimalTest {
    public static void main(String[] args) {
        System.out.println("step1: creating factory");
        System.out.flush();
        var factory = new VectorStorageProviderFactory();
        System.out.println("step2: factory name=" + factory.name());
        System.out.flush();
        System.out.println("done");
        System.out.flush();
        System.exit(0);
    }
}
