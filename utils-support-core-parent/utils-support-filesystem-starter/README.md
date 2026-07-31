# utils-support-filesystem-starter

文件系统核心模块：CSV/JSON/XML/TXT/YAML/ZIP 读写

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-filesystem-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `YamlConfigParser` | YAML 配置文件解析器。 (SPI: `yaml`) |
| `ZipExtractor` | ZIP 压缩文件提取器（支持密码） 使用 zip4j 库实现，支持带密码保护的 ZIP 文件解压。 (SPI: `zip`) |
| `CompressedFileSystem` | 压缩包文件系统 支持 ZIP 格式的压缩包读写操作，提供链式添加和指定提取功能。 链式添加 指定提取 |
| `SevenZFileSystem` | 7z 压缩文件系统 SPI 实现。 (SPI: `7z`) |
| `YamlFileSystem` | YAML（.yml / .yaml）文件系统 SPI 实现。 |
| `YamlReadBuilder` | YAML 文件读取构建器。 将 YAML 文件反序列化为 格式， 支持指定字符集编码。 |
| `YamlWriteBuilder` | YAML 文件写入构建器。 将 Java 对象序列化为 YAML 格式并写入文件， 支持指定字符集编码。 |
| `Zip4jFileSystem` | ZIP4J 压缩文件系统 SPI 实现（支持密码保护）。 (SPI: `zip4j`) |
| `FileSystemFileStorage` | 本地文件系统文件存储实现。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-filesystem-starter
├── utils-support-common-starter
```