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
 */
@Slf4j
public abstract class AbstractWriter implements FileSystem {
    protected List<String> headers;
    protected WriteOption writeOption;
    protected boolean initialized;
    /**
     * 文件路径
     */
    protected File file;

    protected AbstractWriter() {
        this.headers = new ArrayList<>();
        this.writeOption = WriteOption.maps();
        this.initialized = false;
    }

    protected AbstractWriter(File file) {
        this.file = file;
        this.headers = new ArrayList<>();
        this.writeOption = WriteOption.maps();
        this.initialized = false;
    }

    protected AbstractWriter(String filePath) {
        this.file = filePath != null ? new File(filePath) : null;
        this.headers = new ArrayList<>();
        this.writeOption = WriteOption.maps();
        this.initialized = false;
    }

    public AbstractWriter withFile(File file) {
        this.file = file;
        this.initialized = false;
        return this;
    }

    @Override
    public String getType() {
        return "unknown";
    }

    @Override
    public ReadBuilder read(File file) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WriteBuilder write(File file) {
        throw new UnsupportedOperationException();
    }

    public AbstractWriter withHeader(List<String> headers) {
        this.headers = headers != null ? new ArrayList<>(headers) : new ArrayList<>();
        return this;
    }

    public AbstractWriter withWriteOption(WriteOption writeOption) {
        this.writeOption = writeOption;
        return this;
    }

    public AbstractWriter withAutoCloseStream(boolean autoCloseStream) {
        if (writeOption == null) {
            writeOption = WriteOption.maps();
        }
        writeOption.withAutoCloseStream(autoCloseStream);
        return this;
    }

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

    protected abstract void doInitialize() throws IOException;
    protected abstract void doWriteLine(String line) throws IOException;
    protected abstract void doWriteText(String text) throws IOException;
    protected abstract void doWriteBytes(byte[] bytes) throws IOException;
    protected abstract void doWrite(Map<String, Object> data) throws IOException;
    protected abstract void doFlush() throws IOException;
    protected abstract void doFinish() throws IOException;

    public void finish() {
        try { doFinish(); } catch (IOException e) { throw new RuntimeException(e); }
        try { close(); } catch (IOException e) { throw new RuntimeException(e); }
    }

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
