# utils-support-filesystem-starter

文件系统扩展模块：ZIP4J（密码+分卷）、7Z（分卷）、YAML 读写

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

| 类/接口 | SPI Key | 说明 |
|---------|---------|------|
| `Zip4jFileSystem` | `zip4j` | ZIP4J 压缩文件系统（支持密码保护 + 分卷压缩） |
| `SevenZFileSystem` | `7z` | 7Z 压缩文件系统（支持分卷压缩） |
| `YamlFileSystem` | `yaml` | YAML 文件读写 |
| `YamlReadBuilder` | - | YAML 文件读取构建器 |
| `YamlWriteBuilder` | - | YAML 文件写入构建器 |

---

## 分卷压缩支持

本模块中的 `Zip4jFileSystem` 和 `SevenZFileSystem` 均支持分卷压缩功能。

### 分卷命名格式

| 文件系统 | 分卷命名格式 | 示例 |
|----------|-------------|------|
| Zip4jFileSystem | `.z01`, `.z02`, ... `.zip` | `output.z01`, `output.z02`, `output.zip` |
| SevenZFileSystem | `.7z.001`, `.7z.002`, ... `.7z` | `output.7z.001`, `output.7z.002`, `output.7z` |

### 写入分卷

使用 `splitSize(long bytes)` 链式方法设置分卷大小：

```java
import com.chua.common.support.file.FileSystem;

// ZIP4J 分卷写入（支持密码保护）
FileSystem zip4j = FileSystem.create("zip4j");
zip4j.write(new File("output.zip"))
    .password("mypassword")          // 可选：设置密码
    .splitSize(1024 * 1024 * 100)    // 100MB 每卷
    .addFile("large-file.bin", new File("large-file.bin"))
    .addFile("documents/report.pdf", new File("report.pdf"))
    .addBytes("data/config.json", configBytes)
    .finish();
// 生成: output.z01, output.z02, ..., output.zip

// 7Z 分卷写入
FileSystem sevenZ = FileSystem.create("7z");
sevenZ.write(new File("output.7z"))
    .splitSize(1024 * 1024 * 100)    // 100MB 每卷
    .addFile("large-file.bin", new File("large-file.bin"))
    .finish();
// 生成: output.7z.001, output.7z.002, ..., output.7z
```

#### 分卷写入规则

- 如果文件大小 **小于** `splitSize`，则只生成主文件（`.zip` 或 `.7z`）
- 如果文件大小 **大于** `splitSize`，则生成多个分卷文件
- 分卷文件按数字顺序命名（`.z01`, `.z02` 或 `.7z.001`, `.7z.002`）
- 最后一个分卷是主文件（`.zip` 或 `.7z`）

### 读取分卷

使用 `split()` 链式方法启用分卷读取模式：

```java
import com.chua.common.support.file.FileSystem;

// ZIP4J 分卷读取
FileSystem zip4j = FileSystem.create("zip4j");
zip4j.read(new File("output.zip"))
    .password("mypassword")          // 如果有密码
    .split()                         // 启用分卷模式
    .extractAll(targetDir);          // 提取所有文件

// 7Z 分卷读取
FileSystem sevenZ = FileSystem.create("7z");
sevenZ.read(new File("output.7z"))
    .split()                         // 启用分卷模式
    .extractAll(targetDir);          // 提取所有文件
```

#### 分卷读取特性

- 自动检测同目录下的分卷文件
- 支持 `listEntries()` 列出所有条目
- 支持 `extract(entryName, targetDir)` 提取指定文件
- 支持 `extract(targetDir, entryNames...)` 提取多个指定文件
- 支持 `readEntry(entryName)` 读取单个条目内容

### 分卷读写完整示例

```java
import com.chua.common.support.file.FileSystem;
import java.io.File;

public class SplitVolumeExample {
    public static void main(String[] args) {
        FileSystem zip4j = FileSystem.create("zip4j");
        FileSystem sevenZ = FileSystem.create("7z");
        
        // ==================== ZIP4J 分卷 ====================
        
        // 写入带密码的分卷
        zip4j.write(new File("backup.zip"))
            .password("secure123")
            .splitSize(50 * 1024 * 1024) // 50MB 每卷
            .addFile("photos/vacation.jpg", new File("vacation.jpg"))
            .addFile("documents/contract.pdf", new File("contract.pdf"))
            .addBytes("metadata.json", metadataBytes)
            .finish();
        
        // 读取分卷
        zip4j.read(new File("backup.zip"))
            .password("secure123")
            .split()
            .listEntries()
            .forEach(System.out::println);
        
        zip4j.read(new File("backup.zip"))
            .password("secure123")
            .split()
            .extractAll(new File("./restored"));
        
        // ==================== 7Z 分卷 ====================
        
        // 写入分卷
        sevenZ.write(new File("archive.7z"))
            .splitSize(100 * 1024 * 1024) // 100MB 每卷
            .addFile("data.bin", new File("large-data.bin"))
            .finish();
        
        // 读取分卷
        sevenZ.read(new File("archive.7z"))
            .split()
            .extractAll(new File("./extracted"));
        
        // 提取指定文件
        sevenZ.read(new File("archive.7z"))
            .split()
            .extract("data.bin", new File("./single-file"));
    }
}
```

---

## Zip4jFileSystem 详细说明

### 特性

- ✅ 密码保护（AES-128/AES-256 加密）
- ✅ 分卷压缩
- ✅ 链式构建器 API
- ✅ 支持目录结构
- ✅ 支持字节数组直接添加

### 写入构建器方法

| 方法 | 说明 | 示例 |
|------|------|------|
| `password(String)` | 设置密码保护 | `.password("secret")` |
| `splitSize(long)` | 设置分卷大小（字节） | `.splitSize(1024*1024*100)` |
| `addFile(String, File)` | 添加文件 | `.addFile("dir/file.txt", file)` |
| `addBytes(String, byte[])` | 添加字节数组 | `.addBytes("data.bin", bytes)` |
| `finish()` | 完成写入并关闭 | `.finish()` |

### 读取构建器方法

| 方法 | 说明 | 示例 |
|------|------|------|
| `password(String)` | 设置解压密码 | `.password("secret")` |
| `split()` | 启用分卷读取模式 | `.split()` |
| `listEntries()` | 列出所有条目 | `.listEntries()` |
| `extract(String, File)` | 提取指定文件 | `.extract("file.txt", dir)` |
| `extract(File, String...)` | 提取多个指定文件 | `.extract(dir, "a.txt", "b.txt")` |
| `readEntry(String)` | 读取条目内容为字符串 | `.readEntry("file.txt")` |

---

## SevenZFileSystem 详细说明

### 特性

- ✅ 分卷压缩
- ✅ 链式构建器 API
- ✅ 支持目录结构
- ✅ 支持字节数组直接添加
- ✅ 使用 Apache Commons Compress 的 `MultiReadOnlySeekableByteChannel` 合并分卷

### 写入构建器方法

| 方法 | 说明 | 示例 |
|------|------|------|
| `splitSize(long)` | 设置分卷大小（字节） | `.splitSize(1024*1024*100)` |
| `addFile(String, File)` | 添加文件 | `.addFile("dir/file.txt", file)` |
| `addBytes(String, byte[])` | 添加字节数组 | `.addBytes("data.bin", bytes)` |
| `finish()` | 完成写入并关闭 | `.finish()` |

### 读取构建器方法

| 方法 | 说明 | 示例 |
|------|------|------|
| `split()` | 启用分卷读取模式 | `.split()` |
| `listEntries()` | 列出所有条目 | `.listEntries()` |
| `extract(String, File)` | 提取指定文件 | `.extract("file.txt", dir)` |
| `extract(File, String...)` | 提取多个指定文件 | `.extract(dir, "a.txt", "b.txt")` |
| `readEntry(String)` | 读取条目内容为字符串 | `.readEntry("file.txt")` |

---

## 技术实现

### 分卷读取机制

| 文件系统 | 读取机制 | 说明 |
|----------|---------|------|
| Zip4jFileSystem | Zip4j 原生支持 | Zip4j 库内置支持分卷读取 |
| SevenZFileSystem | `MultiReadOnlySeekableByteChannel` | Apache Commons Compress 提供的分卷合并通道 |

### 分卷写入机制

两种文件系统都采用相同的写入策略：
1. 先将所有内容写入临时文件
2. 计算临时文件大小
3. 如果大小超过 `splitSize`，则按大小分割成多个分卷文件
4. 最后一个分卷作为主文件（`.zip` 或 `.7z`）

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-filesystem-starter
├── utils-support-common-starter
├── net.lingala.zip4j:zip4j (Zip4jFileSystem)
└── org.apache.commons:commons-compress (SevenZFileSystem)
```

---

## 与其他模块的关系

本模块中的文件系统实现通过 SPI 机制注册，可与 `utils-support-common-starter` 中的 `FileSystem.create()` 工厂方法配合使用：

```java
// 通过 SPI 创建文件系统实例
FileSystem zip4j = FileSystem.create("zip4j");
FileSystem sevenZ = FileSystem.create("7z");

// 也可以直接实例化
import com.chua.filesystem.support.file.impl.Zip4jFileSystem;
import com.chua.filesystem.support.file.impl.SevenZFileSystem;

Zip4jFileSystem zip4jFs = new Zip4jFileSystem();
SevenZFileSystem sevenZFs = new SevenZFileSystem();
```
