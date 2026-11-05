// Renders Go Nine's flat launcher icon at 512 px (same drawing as res/drawable/ic_launcher_foreground.xml on the
// #4F6E78 background): java tools/BuildIcon.java <out.png> [size]
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class BuildIcon {
    public static void main(String[] a) throws Exception {
        int size = a.length > 1 ? Integer.parseInt(a[1]) : 512;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double k = size / 108.0; // the vector's 108-unit canvas
        g.setColor(new Color(0x4F6E78));
        g.fill(new RoundRectangle2D.Double(0, 0, size, size, size * 0.22, size * 0.22));
        g.scale(k, k);
        // Zoom the 66-unit safe zone a little for the flat icon.
        g.translate(54, 54); g.scale(1.18, 1.18); g.translate(-54, -54);
        g.setColor(new Color(0xE9DCC3));
        g.fill(new RoundRectangle2D.Double(24, 28 - 4 + 4, 56, 56, 8, 8));
        g.setColor(new Color(0x6B5638));
        g.setStroke(new BasicStroke(1.6f));
        for (int i = 0; i <= 4; i++) {
            g.draw(new Line2D.Double(34, 34 + i * 10, 74, 34 + i * 10));
            g.draw(new Line2D.Double(34 + i * 10, 34, 34 + i * 10, 74));
        }
        g.fill(new Ellipse2D.Double(44 - 2.4, 44 - 2.4, 4.8, 4.8));
        g.setColor(new Color(0x1E2A26));
        g.fill(new Ellipse2D.Double(64 - 11, 64 - 11, 22, 22));
        g.setColor(new Color(0xF2, 0xEE, 0xE4, 217));
        g.fill(new Ellipse2D.Double(59.5 - 3.2, 59.2 - 2.2, 6.4, 4.4));
        g.dispose();
        ImageIO.write(img, "png", new File(a[0]));
    }
}
