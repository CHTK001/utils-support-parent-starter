import javax.imageio.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;

public class GrayDebug {
    public static void main(String[] args) throws Exception {
        BufferedImage gray = new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0,0,16,16); g.dispose();
        
        System.out.println("Source type: " + gray.getType());
        
        // Convert to RGB
        BufferedImage rgb = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(gray, 0, 0, null);
        System.out.println("RGB type: " + rgb.getType());
        
        File f = new File(System.getProperty("java.io.tmpdir") + "/gray_test.heic");
        boolean ok = ImageIO.write(rgb, "heic", f);
        System.out.println("RGB→HEIC: " + ok + " size=" + f.length());
        
        ok = ImageIO.write(gray, "heic", f);
        System.out.println("GRAY→HEIC: " + ok + " size=" + f.length());
        
        // Check writer
        Iterator<ImageWriter> w = ImageIO.getImageWritersByFormatName("heic");
        if (w.hasNext()) {
            ImageWriter writer = w.next();
            System.out.println("Writer: " + writer.getClass().getSimpleName());
        }
        
        // GIF test
        BufferedImage gifSrc = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        g = gifSrc.createGraphics();
        g.setColor(Color.RED); g.fillRect(0,0,16,16); g.dispose();
        File gif = new File(System.getProperty("java.io.tmpdir") + "/test.gif");
        ImageIO.write(gifSrc, "gif", gif);
        BufferedImage gifRead = ImageIO.read(gif);
        System.out.println("\nGIF read type: " + gifRead.getType());
        File heic = new File(System.getProperty("java.io.tmpdir") + "/from_gif.heic");
        ok = ImageIO.write(gifRead, "heic", heic);
        System.out.println("GIF→HEIC: " + ok + " size=" + heic.length());
    }
}
