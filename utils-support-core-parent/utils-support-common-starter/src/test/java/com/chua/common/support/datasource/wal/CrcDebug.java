package com.chua.common.support.datasource.wal;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
public class CrcDebug {
    public static void main(String[] args) throws Exception {
        // Simulate buildBody for key:45, value-45
        byte op = 1;
        String key = "key:45";
        String val = "value-45";
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        byte[] vb = val.getBytes(StandardCharsets.UTF_8);
        int total = 4 + kb.length + 4 + vb.length;
        byte[] writeBuf = new byte[Math.max(total * 2, 1024)];
        java.nio.ByteBuffer.wrap(writeBuf, 0, total).putInt(kb.length).put(kb).putInt(vb.length);
        System.arraycopy(vb, 0, writeBuf, 4 + kb.length + 4, vb.length);
        // This is what gets written to WAL
        byte[] payload = java.util.Arrays.copyOf(writeBuf, total);
        CRC32 crc = new CRC32();
        crc.update(op);
        crc.update(payload);
        long crcValue = crc.getValue();
        System.out.println("Payload length: " + payload.length);
        System.out.println("CRC (long): " + crcValue);
        System.out.println("CRC (int): " + (int)crcValue);
        // Show first 4 bytes of payload
        System.out.print("Payload bytes: ");
        for (int i = 0; i < Math.min(30, payload.length); i++) {
            System.out.printf("%02X ", payload[i]);
        }
        System.out.println();
    }
}
