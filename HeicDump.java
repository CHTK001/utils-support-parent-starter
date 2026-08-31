import java.io.*;
public class HeicDump {
    public static void main(String[] args) throws Exception {
        byte[] data = java.nio.file.Files.readAllBytes(new File(args[0]).toPath());
        System.out.println("Total: " + data.length);
        long pos = 0;
        for (int d = 0; d < 20; d++) {
            if (pos + 8 > data.length) break;
            int b0 = data[(int)pos] & 0xFF;
            int b1 = data[(int)(pos+1)] & 0xFF;
            int b2 = data[(int)(pos+2)] & 0xFF;
            int b3 = data[(int)(pos+3)] & 0xFF;
            int size = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
            String type = new String(data, (int)(pos+4), 4);
            System.out.printf("  pos=%d size=%d type=%s remain=%d hex=%02X%02X%02X%02X %s%n",
                pos, size, type, data.length - (int)pos, b0, b1, b2, b3, type);
            if (size <= 0 || pos + size > data.length) {
                System.out.println("  *** BROKEN ***");
                break;
            }
            if ("idat".equals(type)) {
                int ds = (int)(pos + 8);
                System.out.printf("    idat bytes[0..3]: %02X %02X %02X %02X JPEG_SOI=%s%n",
                    data[ds]&0xFF, data[ds+1]&0xFF, data[ds+2]&0xFF, data[ds+3]&0xFF,
                    (data[ds]==(byte)0xFF && data[ds+1]==(byte)0xD8));
            }
            pos += size;
        }
    }
}
