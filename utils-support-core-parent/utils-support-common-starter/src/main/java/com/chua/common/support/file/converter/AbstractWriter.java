package com.chua.common.support.file.converter;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.file.system.WriteOption;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public abstract class AbstractWriter implements FileSystem {
    /** 表头列表 */
    protected List<String> headers;
    /** 写入选项 */
    protected WriteOption writeOption;
    /** 是否已初始化 */
    protected boolean initialized;
    /**
     * 文件路径
     */
    protected File file;

    /** 创建 AbstractWriter 实例 */
    protected AbstractWriter() {
        this.headers = new ArrayList<>();
        this.writeOption = WriteOption.maps();
        this.initialized = false;
    }

    /**
     * 创建 AbstractWriter 实例
     * @param file file
     */
    protected AbstractWriter(File file) {
        this.file = file;
        this.headers = new ArrayList<>();
        this.writeOption = WriteOption.maps();
        this.initialized = false;
    }

    /**
     * 创建 AbstractWriter 实例
     * @param filePath filePath
     */
    protected AbstractWriter(String filePath) {
        this.file = filePath != null ? new File(filePath) : null;
        this.headers = new ArrayList<>();
        this.writeOption = WriteOption.maps();
        this.initialized = false;
    }

    /**
     * WithFile
     * @param file 文件，不允许为 null
     * @return AbstractWriter 对象
     */
    public AbstractWriter withFile(File file) {
        this.file = file;
        this.initialized = false;
        return this;
    }

    @Override
    /** 获取Type */
    public String getType() {
        return "unknown";
    }

    @Override
    /** 读取 */
    public ReadBuilder read(File file) {
        throw new UnsupportedOperationException();
    }

    @Override
    /** 写入 */
    public WriteBuilder write(File file) {
        throw new UnsupportedOperationException();
    }

    /**
     * WithHeader
     * @param headers 请求头，不允许为 null
     * @return AbstractWriter 对象
     */
    public AbstractWriter withHeader(List<String> headers) {
        this.headers = headers != null ? new ArrayList<>(headers) : new ArrayList<>();
        return this;
    }

    /**
     * With写入Option
     * @param writeOption 写入Option，不允许为 null
     * @return AbstractWriter 对象
     */
    public AbstractWriter withWriteOption(WriteOption writeOption) {
        this.writeOption = writeOption;
        return this;
    }

    /**
     * WithAuto关闭Stream
     * @param autoCloseStream auto关闭流（布尔开关）
     * @return AbstractWriter 对象
     */
    public AbstractWriter withAutoCloseStream(boolean autoCloseStream) {
        if (writeOption == null) {
            writeOption = WriteOption.maps();
        }
        writeOption.withAutoCloseStream(autoCloseStream);
        return this;
    }

    /**
     * 写入
     * @param item 项，不允许为 null
     * @return AbstractWriter 对象
     */
    public <T> AbstractWriter write(T... item) throws IOException {
        if (item == null || item.length == 0) {
            return this;
        }
        ensureInitialized();
        for (var it : item) {
            if (it == null) {
                continue;
            }
            writeSingle(it);
        }
        if (writeOption != null && writeOption.isAutoCloseStream()) {
            close();
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    /**
     * 写入Single
     * @param item 项，不允许为 null
     */
    private void writeSingle(Object item) throws IOException {
        if (item instanceof Map) {
            doWrite((Map<String, Object>) item);
        } else if (item instanceof byte[]) {
            doWriteBytes((byte[]) item);
        } else if (item instanceof String) {
            doWriteText((String) item);
        } else {
            throw new UnsupportedOperationException("Unsupported write type: " + item.getClass().getName());
        }
    }

    /** EnsureInitialized */
    protected void ensureInitialized() throws IOException {
        if (initialized) {
            return;
        }
        if (file == null) {
            throw new IOException("File is null");
        }
        doInitialize();
        initialized = true;
    }

    /** Do初始化 */
    protected abstract void doInitialize() throws IOException;
    /**
     * Do写入Line
     * @param line 方法入参 line
     */
    protected abstract void doWriteLine(String line) throws IOException;
    /**
     * Do写入Text
     * @param text 文本，不允许为 null
     */
    protected abstract void doWriteText(String text) throws IOException;
    /**
     * Do写入Bytes
     * @param bytes 字节数组，不允许为 null
     */
    protected abstract void doWriteBytes(byte[] bytes) throws IOException;
    /**
     * Do写入
     * @param data 数据，不允许为 null
     */
    protected abstract void doWrite(Map<String, Object> data) throws IOException;
    /** Do刷新 */
    protected abstract void doFlush() throws IOException;
    /** DoFinish */
    protected abstract void doFinish() throws IOException;

    /** Finish */
    public void finish() {
        try {
            doFinish();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** 关闭 */
    public void close() throws IOException {
        try {
            if (initialized) {
                doFlush();
                doFinish();
            }
        } finally {
            initialized = false;
        }
    }
}
