# utils-support-modbus-starter

> 基于 j2mod 3.2.1 的 Modbus 客户端（主站）与服务端（从站）工具库，支持 modbus-tcp、modbus-udp、modbus-rtu、modbus-ascii 四种协议。客户端与服务端均通过本模块 SPI（`ModbusClient` / `ModbusServer`）按协议别名暴露，TCP/UDP 服务端同时注册到 common-starter 的 `Server` SPI。当前版本 4.0.0.51（API 自 4.0.0.50 引入），编译目标 JDK 25，根包 `com.chua.modbus.support`。

## 目录

- [它是什么 / 不是什么](#它是什么--不是什么)
- [核心 API 与速查表](#核心-api-与速查表)
- [快速开始](#快速开始)
- [配置项（ModbusSetting）](#配置项modbusssetting)
- [SPI 扩展点](#spi-扩展点)
- [构建与依赖](#构建与依赖)
- [边界（这些能力不在本模块）](#边界这些能力不在本模块)

## 它是什么 / 不是什么

| ✅ 是 | ❌ 不是 |
|---|---|
| Modbus 主站客户端：读线圈 / 离散输入 / 保持寄存器 / 输入寄存器，写单个 / 多个线圈与寄存器 | Spring Boot Starter：本模块无自动配置类、无 application.yml 配置键（源码未提供） |
| Modbus 从站服务端：TCP、UDP、串口 RTU、串口 ASCII | Modbus Security(TLS)：服务端启动时明确拒绝流加密协商（无密钥协商字段，也未接入 Modbus Security） |
| 可插拔服务端数据区（`ModbusDataStore`），支持运行中热替换 | 文件记录与 FIFO 访问：过程映像对二者直接抛地址非法异常 |
| 统一配置（`ModbusSetting`，链式 setter）与统一异常（`ModbusException`，含协议异常码） | Modbus 网关 / 协议转换代理 |
| 串口参数与 RS-485 选项配置 | 设备采集调度、点位管理等业务逻辑（数据区内容由业务侧提供） |

## 核心 API 与速查表

客户端接口 `ModbusClient`（`com.chua.modbus.support.ModbusClient`，`AutoCloseable`）按功能码划分的读写方法：

| 方法 | 功能码 | 约束 |
|---|---|---|
| `readCoils(unitId, address, quantity)` | 0x01 | 位数量 1~2000 |
| `readDiscreteInputs(unitId, address, quantity)` | 0x02 | 位数量 1~2000 |
| `readHoldingRegisters(unitId, address, quantity)` | 0x03 | 寄存器数量 1~125，返回无符号 16 位值 |
| `readInputRegisters(unitId, address, quantity)` | 0x04 | 寄存器数量 1~125，返回无符号 16 位值 |
| `writeSingleCoil(unitId, address, value)` | 0x05 | — |
| `writeSingleRegister(unitId, address, value)` | 0x06 | 值 0~65535 |
| `writeMultipleCoils(unitId, address, values)` | 0x0F | 1~1968 个 |
| `writeMultipleRegisters(unitId, address, values)` | 0x10 | 1~123 个 |

每个方法都有省略 `unitId` 的重载，从站地址取 `ModbusSetting#getUnitId()` 的默认值。

### 速查表（最常查的入口）

| 需求 | 入口 |
|---|---|
| 创建客户端（按配置协议自动选择实现） | `ModbusClient.create(ModbusSetting)` |
| 读保持寄存器（默认从站地址） | `client.readHoldingRegisters(address, quantity)` |
| 写单个 / 多个寄存器 | `client.writeSingleRegister(...)` / `client.writeMultipleRegisters(...)` |
| 读写线圈 | `client.readCoils(...)` / `client.writeSingleCoil(...)` / `client.writeMultipleCoils(...)` |
| 创建服务端（从站） | `ModbusServer.create(ModbusSetting)` |
| 预置 / 修改从站数据 | `server.getDataStore().writeHoldingRegisters(address, values)` 等 |
| 串口（RTU/ASCII）配置 | `ModbusSetting.ofSerial("COM3").setBaudRate(19200)...` |
| 区分通信失败与对端异常响应 | `ModbusException#getExceptionCode()` / `ModbusException#describe(code)` |

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-modbus-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 2. 客户端（主站）读写

```java
import com.chua.modbus.support.ModbusClient;
import com.chua.modbus.support.ModbusProtocol;
import com.chua.modbus.support.ModbusSetting;

ModbusSetting setting = ModbusSetting.of("192.168.1.10", 502)
        .setProtocol(ModbusProtocol.UDP)
        .setUnitId(1);
try (ModbusClient client = ModbusClient.create(setting)) {
    client.connect();
    int[] values = client.readHoldingRegisters(0, 10);
    client.writeSingleRegister(0, 123);
}
```

协议由 `ModbusSetting#setProtocol(...)` 选择：`TCP` / `UDP` / `RTU` / `ASCII` 分别映射到 `modbus-tcp` / `modbus-udp` / `modbus-rtu` / `modbus-ascii` 实现。客户端实例可被多线程共享，请求在同一个 TCP 连接或串口上串行化；传输层异常会复位连接，下次调用自动重连。

### 3. 服务端（从站）：TCP / UDP

```java
import com.chua.modbus.support.ModbusProtocol;
import com.chua.modbus.support.ModbusSetting;
import com.chua.modbus.support.server.ModbusServer;

ModbusServer server = ModbusServer.create(ModbusSetting.of("0.0.0.0", 502)
        .setProtocol(ModbusProtocol.UDP));
server.getDataStore().writeHoldingRegisters(0, new int[]{1, 2, 3});
server.start();
```

TCP 服务端也可通过 common-starter 的 `Server` SPI 创建：

```java
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;

Server server = ServerBuilder.create().type("modbus").port(502).build();
```

### 4. 服务端（从站）：串口 RTU / ASCII

```java
import com.chua.modbus.support.ModbusSetting;
import com.chua.modbus.support.server.ModbusRtuServer;

ModbusRtuServer server = new ModbusRtuServer(ModbusSetting.ofSerial("COM3").setBaudRate(19200));
server.getDataStore().writeHoldingRegisters(0, new int[]{100});
server.start();
```

串口是独占资源，同一端口同时只能有一个从站实例。ASCII 编码通过 `ModbusSetting.ofSerial("COM3").setProtocol(ModbusProtocol.ASCII)` 切换。

## 配置项（ModbusSetting）

本模块没有 Spring Boot 配置文件键（源码未提供），全部配置通过 `com.chua.modbus.support.ModbusSetting` 以代码方式给出。所有 setter 返回 `this`，支持链式配置。

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `protocol` | `ModbusProtocol` | `TCP` | 传输协议；设为 `RTU`/`ASCII` 时会同步 `serialEncoding` |
| `host` | `String` | `127.0.0.1` | TCP/UDP 主机地址 |
| `port` | `int` | `502` | TCP/UDP 端口，1~65535 |
| `unitId` | `int` | `1` | 从站地址，0~255 |
| `connectTimeout` | `int` | `5000` | 连接超时（毫秒），必须 > 0 |
| `readTimeout` | `int` | `3000` | 读超时（毫秒），必须 > 0 |
| `retries` | `int` | `0` | 请求失败后的重试次数（不含首次），负数按 0 处理；由 j2mod 事务层执行，对端返回的 Modbus 异常响应不重试 |
| `portName` | `String` | 无（串口必填） | 串口名称，如 `COM3`、`/dev/ttyUSB0` |
| `baudRate` | `int` | `9600` | 波特率，必须 > 0 |
| `dataBits` | `int` | `8` | 数据位，5~8 |
| `stopBits` | `int` | `STOP_BITS_ONE`(1) | 停止位常量：`STOP_BITS_ONE` / `STOP_BITS_ONE_POINT_FIVE` / `STOP_BITS_TWO` |
| `parity` | `int` | `PARITY_NONE` | 校验位常量：`PARITY_NONE` / `PARITY_ODD` / `PARITY_EVEN` / `PARITY_MARK` / `PARITY_SPACE` |
| `serialEncoding` | `String` | `SERIAL_ENCODING_RTU` | 串口编码：`SERIAL_ENCODING_RTU`（二进制 + CRC16）或 `SERIAL_ENCODING_ASCII`（LRC） |
| `echo` | `boolean` | `false` | 是否回显本机发送的数据（RS-485 半双工回环设备才需要） |
| `rs485Mode` | `boolean` | `false` | 是否启用 RS-485 模式 |
| `rs485TxEnableActiveHigh` | `boolean` | `true` | RS-485 发送使能电平是否高有效 |
| `rs485EnableTermination` | `boolean` | `false` | RS-485 是否启用终端电阻 |
| `rs485RxDuringTx` | `boolean` | `false` | RS-485 发送期间是否保持接收 |
| `openDelay` | `int` | `0` | 串口打开延时（毫秒） |

便捷工厂方法：`ModbusSetting.of(host, port)`（网络）、`ModbusSetting.ofSerial(portName)`（串口，默认 RTU）、`ModbusSetting.defaults()`（全默认）。

`ModbusSetting#validate()` 会按当前协议校验：网络协议校验 host 非空与端口范围；串口协议校验串口名、波特率、数据位、停止位、校验位与串口编码。校验失败抛 `ModbusException`。

## SPI 扩展点

### ModbusClient SPI（客户端）

- 接口：`com.chua.modbus.support.ModbusClient`，通过 `ServiceProvider.of(ModbusClient.class).getNewExtension(alias, setting)` 创建。
- 注册文件：`META-INF/extensions/com.chua.modbus.support.ModbusClient`。

| SPI 别名 | 实现类 |
|---|---|
| `modbus-tcp`、`modbus` | `com.chua.modbus.support.client.ModbusTcpClient` |
| `modbus-udp`、`udp` | `com.chua.modbus.support.client.ModbusUdpClient` |
| `modbus-rtu`、`rtu`、`modbus-ascii`、`ascii` | `com.chua.modbus.support.client.ModbusRtuClient` |

三个实现均继承 `com.chua.modbus.support.client.AbstractJ2ModClient`，新传输方式只需实现 `createMaster()` 提供对应的 j2mod 主站。

### ModbusServer SPI（服务端）

- 接口：`com.chua.modbus.support.server.ModbusServer`，通过 `ServiceProvider.of(ModbusServer.class).getNewExtension(alias, setting)` 创建。
- 注册文件：`META-INF/extensions/com.chua.modbus.support.server.ModbusServer`。

| SPI 别名 | 实现类 |
|---|---|
| `modbus-tcp` | `com.chua.modbus.support.server.ModbusTcpServer` |
| `modbus-udp` | `com.chua.modbus.support.server.ModbusUdpServer` |
| `modbus-rtu`、`modbus-ascii` | `com.chua.modbus.support.server.ModbusRtuServer` |

`ModbusTcpServer` 与 `ModbusUdpServer` 同时继承 common-starter 的 `AbstractServer`，并额外注册到 common-starter 的 `Server` SPI（注册文件 `META-INF/extensions/com.chua.common.support.network.server.Server`，别名 `modbus` / `modbus-tcp` / `modbus-udp`），协议类型为 `ProtocolType.MODBUS`。`ModbusTcpServer` 另有连接处理线程数 `poolSize`（默认 8，最小 1，需在 `start()` 前设置）。

### ModbusDataStore SPI（服务端数据区）

- 接口：`com.chua.modbus.support.server.ModbusDataStore`，暴露线圈、离散输入、保持寄存器、输入寄存器四类数据区的读写与 `getSize()`。
- 默认实现：`MemoryModbusDataStore`，默认分配完整 65536 个地址（四类数据区各一份，单个实例约 384KB），可构造时指定 1~65536 缩小空间；所有方法 `synchronized`，可被多连接线程共享。
- 自定义实现要求：支持多连接线程并发访问；地址越界时抛出异常码为 `ModbusException.ILLEGAL_DATA_ADDRESS`（0x02）的异常，服务端会据此回 0x02 异常响应。通过 `server.setDataStore(...)` 注入，运行中替换立即生效。
- 地址均为 0 基址（与 Modbus PDU 一致），业务侧使用 4xxxx / 0xxxx 编号时需自行换算。

## 构建与依赖

```text
utils-support-modbus-starter
├── com.chua:utils-support-common-starter   （SPI 框架、Server SPI、工具类）
└── com.ghgande:j2mod:3.2.1                 （Modbus 协议框架：主站/从站、PDU/CRC/异常响应）
```

- 父模块：`com.chua:utils-support-network-parent:4.0.0.51`
- JDK：25（继承仓库根 POM 的 `java.version=25`）
- 本地构建：

```bash
mvn -o -q compile -DskipTests     # 编译
mvn clean install -DskipTests     # 安装到本地仓库
```

## 边界（这些能力不在本模块）

- **Modbus Security / TLS**：服务端启动时显式拒绝流加密协商，本实现未接入 Modbus Security(TLS)。
- **文件记录与 FIFO**：过程映像的 `getFile` / `getFIFO` 直接抛 `IllegalAddressException`，未实现对应功能码。
- **Spring Boot 自动配置**：无自动配置类、无配置属性绑定，接入方自行 new / SPI 创建实例。
- **网关与路由**：不提供 Modbus 网关、多从站路由或协议转换代理能力。
- **高并发 UDP 服务端**：UDP 从站单线程接收数据报并逐个应答，适合局域网采集，不适合高并发；需要并发处理时应选 TCP。
- **串口共享**：串口是独占资源，同一端口同时只能有一个从站实例。
- **重试语义**：重试只作用于传输层失败；对端返回的 Modbus 异常响应（如 0x02 / 0x03）不会重试，直接抛出 `ModbusException`。
- **UDP 写多个线圈**：因 j2mod 3.2.1 的 `WriteMultipleCoilsRequest` 不刷新 dataLength、UDP 事务重试耗尽后不抛异常，本模块显式修正报文长度并在事务结束后校验响应是否到达。
