import javax.imageio.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;

public class GifDebug {
    public static void main(String[] args) throws Exception {
        // Create a simple GIF-compatible image
        BufferedImage src = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = src.createGraphics();
        g.setColor(Color.RED); g.fillRect(0,0,16,16); g.dispose();
        
        File gif = new File(System.getProperty("java.io.tmpdir") + "/test.gif");
        ImageIO.write(src, "gif", gif);
        System.out.println("Created GIF: " + gif.length() + " bytes");
        
        BufferedImage read = ImageIO.read(gif);
        System.out.println("Read GIF: type=" + read.getType() + " w=" + read.getWidth() + " h=" + read.getHeight());
        
        File heic = new File(System.getProperty("java.io.tmpdir") + "/from_gif.heic");
        boolean ok = ImageIO.write(read, "heic", heic);
        System.out.println("GIF→HEIC: " + ok + " size=" + heic.length());
    }
}
