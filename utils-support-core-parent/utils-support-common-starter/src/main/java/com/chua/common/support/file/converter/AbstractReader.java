package com.chua.common.support.file.converter;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.file.system.ReadOption;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 抽象读取器类，用于处理文件的读取操作。
 * @author CH
 */
@Slf4j
public abstract class AbstractReader implements FileSystem {

    /**
     * 读取选项配置。
     */
    protected ReadOption readOption;

    /**
     * 文件对象，表示当前要读取的文件。
     */
    protected File file;

    /**
     * 默认构造函数。
     */
    protected AbstractReader() {
    }

    /**
     * 带文件参数的构造函数。
     *
     * @param file 文件对象
     */
    protected AbstractReader(File file) {
        this.file = file;
    }

    /**
     * 带文件路径字符串的构造函数。
     *
     * @param filePath 文件路径字符串
     */
    protected AbstractReader(String filePath) {
        if (filePath != null) {
            this.file = new File(filePath);
        } else {
            this.file = null;
        }
    }

    /**
     * 设置文件对象并返回当前实例，支持链式调用。
     *
     * @param file 文件对象
     * @return 当前AbstractReader实例
     */
    public AbstractReader withFile(File file) {
        this.file = file;
        return this;
    }

    /**
     * 获取当前读取器的类型标识。
     *
     * @return 类型字符串
     */
    @Override
    public String getType() {
        return "unknown";
    }

    /**
     * 创建读取构建器（未实现）。
     *
     * @param file 文件对象
     * @return 读取构建器
     * @throws UnsupportedOperationException 始终抛出此异常，因为子类需要实现具体逻辑
     */
    @Override
    public ReadBuilder read(File file) {
        throw new UnsupportedOperationException();
    }

    /**
     * 创建写入构建器（未实现）。
     *
     * @param file 文件对象
     * @return 写入构建器
     * @throws UnsupportedOperationException 始终抛出此异常，因为此类仅负责读取
     */
    @Override
    public WriteBuilder write(File file) {
        throw new UnsupportedOperationException();
    }

    /**
     * 读取所有数据到内存中。
     *
     * @return 当前AbstractReader实例
     * @throws IOException 当发生IO错误时抛出
     */
    public AbstractReader readAll() throws IOException {
        if (file == null || !file.exists() || !file.isFile()) {
            return this;
        }
        List<Map<String, Object>> maps = doReadMaps();
        return this;
    }

    /**
     * 打开文件的输入流。
     *
     * @return 文件输入流
     * @throws FileNotFoundException 当文件为空或不存在时抛出
     * @throws IOException           当发生IO错误时抛出
     */
    public InputStream openInputStream() throws IOException {
        if (file == null) {
            throw new FileNotFoundException("File is null");
        }
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + file.getAbsolutePath());
        }
        return new FileInputStream(file);
    }

    /**
     * 执行具体的读取操作，将文件内容转换为Map列表。
     * 必须由子类实现具体逻辑。
     *
     * @return Map列表，每个Map代表一行或一条记录
     * @throws IOException 当发生IO错误时抛出
     */
    protected abstract List<Map<String, Object>> doReadMaps() throws IOException;
}
