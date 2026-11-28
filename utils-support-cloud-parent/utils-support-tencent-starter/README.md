# utils-support-tencent-starter

微信支付渠道实现。源码包含 `TenpayConfig` 和 `TenpayProvider`，基于 WxJava 的 `WxPayService` 实现公共 `PaymentChannel` 契约，SPI 名为 `tenpay`。

## 安装

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-tencent-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

支付请求与响应契约由传递依赖的 [payment-starter](../../utils-support-core-parent/utils-support-payment-starter/README.md) 提供。

## 创建渠道并发起支付

使用有配置的构造器。无参构造器仅供 SPI 发现，内部支付服务为 `null`，不能直接用于实际下单。

```java
import com.chua.payment.spi.PaymentChannel;
import com.chua.payment.support.PaymentRequest;
import com.chua.payment.support.PaymentResponse;
import com.chua.payment.support.Scene;
import com.chua.tencent.support.payment.TenpayConfig;
import com.chua.tencent.support.payment.TenpayProvider;
import java.math.BigDecimal;
import java.util.Map;

TenpayConfig config = TenpayConfig.builder()
        .appId(System.getenv("WECHAT_APP_ID"))
        .mchId(System.getenv("WECHAT_MCH_ID"))
        .mchKey(System.getenv("WECHAT_MCH_KEY"))
        .notifyUrl(System.getenv("WECHAT_PAY_NOTIFY_URL"))
        .build();
PaymentChannel channel = new TenpayProvider(config);

PaymentResponse result = channel.pay(PaymentRequest.builder()
        .outTradeNo("ORDER-10001")
        .amount(new BigDecimal("0.01"))
        .subject("订单支付")
        .scene(Scene.JSAPI)
        .extParams(Map.of("openid", System.getenv("WECHAT_OPEN_ID")))
        .build());
// 返回客户端调起支付所需的参数；下单成功不代表用户已完成付款。
Map<String, String> clientParameters = result.getExtParams();
```

示例会请求微信商户接口，应由调用方提供自己的商户配置、订单号和已获取的用户 openid。

## 支付场景与结果

| `Scene` | 实现行为 | 主要结果/扩展参数 |
| --- | --- | --- |
| `JSAPI`、`MINI_APP` | 以 `JSAPI` 统一下单并获取支付参数 | `extParams.openid` 必须由调用方提供，返回 `PaymentResponse.extParams` |
| `APP` | 以 `APP` 统一下单并获取支付参数 | 返回客户端参数 `extParams` |
| `H5` | 以 `MWEB` 统一下单 | `codeUrl` 为跳转 URL；传入 `extParams.sceneInfo` 使用自己的站点信息 |
| `NATIVE` | 以 `NATIVE` 统一下单 | `codeUrl` 为二维码内容；可传 `extParams.productId` |
| `BAR_CODE` | 调用付款码 `micropay` | `extParams.authCode` 为付款码，结果包含微信交易号 `tradeNo` |

其它场景抛出 `PayException`。金额以元传入，内部乘以 100 后转为整数分；调用方必须先约束金额精度与范围。支付异常包装为 `PayException("WXPAY_ERROR", ...)`，由业务方处理。

## 配置与请求覆盖

| 配置 | 用途 |
| --- | --- |
| `appId`、`mchId`、`mchKey` | 应用、商户与 API 密钥 |
| `subAppId`、`subMchId` | 服务商子应用与子商户配置 |
| `notifyUrl` | 默认通知地址；请求的 `notifyUrl` 非空时覆盖 |
| `certPath` | 映射为 WxJava 的 `keyPath` |
| `certSerialNo`、`apiV3Key`、`appSecret` | 配置对象提供的字段；当前下单代码仍调用 XML 支付接口，不能据此视为完整 API v3 实现 |

`timeoutExpress` 直接传给 WxJava 的 `timeExpire`，本实现不把 `30m` 等相对表达式转为微信绝对时间格式。`spbillCreateIp` 当前固定为 `127.0.0.1`。H5 未传 `sceneInfo` 时存在示例站点兜底，实际使用请显式传入真实站点信息。

## 能力边界

本模块只覆写 `pay()`，没有实现退款、关闭、查单、通知验签或订单状态入库；公共接口的其它操作保留默认不支持行为。支付通知接收与支付完成的业务确认由调用方负责。

本目录没有微信登录、unionid/openid 映射、用户表或管理接口。腾讯云短信见 [tencentcloud-starter](../utils-support-tencentcloud-starter/README.md)，混元/COS 见 [hunyuan-starter](../utils-support-hunyuan-starter/README.md)，微信消息推送见 [wechat-starter](../utils-support-wechat-starter/README.md)。

源码：[TenpayProvider](src/main/java/com/chua/tencent/support/payment/TenpayProvider.java)、[TenpayConfig](src/main/java/com/chua/tencent/support/payment/TenpayConfig.java)。返回 [云平台目录](../README.md)。
