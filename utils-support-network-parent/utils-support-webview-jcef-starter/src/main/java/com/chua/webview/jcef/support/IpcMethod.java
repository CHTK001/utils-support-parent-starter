package com.chua.webview.jcef.support;

import com.chua.common.support.network.http.HttpMethod;

import java.lang.annotation.*;

import static com.chua.common.support.network.http.HttpMethod.POST;

/**
 * IPC             
 * <p>
 *                          IPC                 JS   Java                
 *        {@link IpcProtocolServer}                 {@code restful()}                         
 * </p>
 * <p>
 *                
 * <pre>
 * public class MyIpcHandler {
 *     &#64;IpcMethod("/api/hello")
 *     public String hello(Map&lt;String, Object&gt; body) {
 *         return "{\"msg\":\"hello\"}";
 *     }
 * }
 *
 * IpcProtocolServer ipc = new IpcProtocolServer(...);
 * ipc.restful(new MyIpcHandler());
 * </pre>
 * </p>
 *
 * @author CH
 * @since 2025
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IpcMethod {

    /**
     * IPC             
     *
     * @return              "/api/hello"   
     */
    String value();

    /**
     * HTTP                    HTTP                      
     * <p>
     * IPC                       POST                                              
     * </p>
     *
     * @return HTTP             
     */
    HttpMethod[] method() default {POST};
}
