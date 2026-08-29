package com.chua.common.support.wal;

import com.chua.common.support.datasource.wal.WalStoreConfig;
import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * JDBC 格式 WAL 文件系统实现。
 * payload: int32 colCount + [int32 nameLen + name + int32 valLen + val]...
 */
@Spi("wal-jdbc")
public class JdbcWalFileSystem extends AbstractWalFileSystem {

    public JdbcWalFileSystem(WalStoreConfig config) throws IOException {
        super(config);
    }

    @Override
    protected byte opType() { return 0x04; }

    @Override
    protected String decodeKey(byte[] payload) {
        if (payload == null || payload.length < 4) return null;
        int colCount = ByteBuffer.wrap(payload).getInt();
        if (colCount <= 0) return null;
        // first column name is typically rowId
        ByteBuffer bb = ByteBuffer.wrap(payload);
        bb.getInt(); // skip colCount
        int nameLen = bb.getInt();
        if (nameLen <= 0 || nameLen > bb.remaining()) return null;
        byte[] nameBytes = new byte[nameLen];
        bb.get(nameBytes);
        return new String(nameBytes, StandardCharsets.UTF_8);
    }

    public static byte[] encode(int colCount, String... colsAndVals) {
        ByteBuffer bb = ByteBuffer.allocate(64);
        bb.putInt(colCount);
        for (int i = 0; i < colsAndVals.length; i += 2) {
            byte[] nb = colsAndVals[i].getBytes(StandardCharsets.UTF_8);
            byte[] vb = colsAndVals[i + 1].getBytes(StandardCharsets.UTF_8);
            bb.putInt(nb.length); bb.put(nb);
            bb.putInt(vb.length); bb.put(vb);
        }
        byte[] result = new byte[bb.position()];
        bb.position(0); bb.get(result);
        return result;
    }
}
