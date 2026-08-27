package com.chua.common.support.datasearch;

import com.chua.common.support.datasearch.network.proxy.ProxyFetcherFlow;

/**
 * 简单测试代理获取
 */
public class ProxyTest {
    public static void main(String[] args) throws Exception {
        System.out.println("Fetching proxies...");
        var proxies = ProxyFetcherFlow.of().fetchAll();
        System.out.println("Got " + proxies.size() + " proxies");
        if (!proxies.isEmpty()) {
            System.out.println("Sample: " + proxies.get(0));
        }
        System.out.println("Done");
    }
}
