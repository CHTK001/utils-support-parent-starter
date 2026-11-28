# utils-support-osgi-bundle-starter

宿主与插件共享的 OSGi 契约，全部类型仅依赖 JDK。该模块提供生命周期、服务注册和框架管理接口，不携带 Felix、Spring 或 SSH 服务端。

## 安装

宿主使用普通依赖。插件由宿主提供这些类型，插件 POM 使用 `provided`，并在 bundle 清单中导入契约包，避免打包第二份接口：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-osgi-bundle-starter</artifactId>
    <version>4.0.0.51</version>
    <scope>provided</scope>
</dependency>
```

## 注册和释放服务

```java
import com.chua.common.support.osgi.BundleApplication;
import com.chua.common.support.osgi.BundleContext;

public class GreetingApplication implements BundleApplication {
    private final Runnable greeting = () -> System.out.println("hello");

    @Override
    public void onBundleStart(BundleContext context) {
        context.registerService(Runnable.class, greeting);
    }

    @Override
    public void onBundleStop(BundleContext context) {
        context.unregisterService(Runnable.class, greeting);
    }
}
```

宿主负责发现应用并调用生命周期。具体发现和打包方式见 [OSGi 实现模块](../utils-support-osgi-starter/README.md)，仅创建上述类不会自行启动框架。

## 常用契约

- `BundleApplication`：插件启动、停止回调。
- `BundleContext`：`registerService` 可带属性；`getService` / `getServices` 查询服务，`getServiceTypes` 枚举类型，`getBundle` 查已安装 bundle，`refresh` 刷新视图。
- `unregisterService(type, service)` 按实例身份注销，应传回注册时的同一对象；返回值表示是否实际注销。
- `OsgiLauncher` / `OsgiBundle`：框架操作与 bundle 状态视图；`BundleLifecycleListener`、`ServiceChangeListener`、`ServiceChangeEvent` 提供变更通知。
- `DynamicSpiResolver`：动态 SPI 解析契约，供宿主桥接框架当前服务视图。

宿主必须导出 `com.chua.common.support.osgi`，业务服务接口也必须由共享 API bundle 导出。服务可能随插件卸载而消失，消费端应处理查询为空和生命周期变化。

[返回核心目录](../README.md) · [返回总目录](../../README.md)
