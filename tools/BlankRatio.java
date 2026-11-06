// Measures the biggest vertical blank band inside the control window in a screenshot (profile D44: ≤ 25 % of
// the window height). The window is found as the large light-grey glass rectangle; a row is "blank" when the
// window's inner columns have no pixel darker than the glass by more than a threshold.
//   java tools/BlankRatio.java shot.png [shot2.png ...]
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class BlankRatio {
    static int lum(int rgb) { return ((rgb >> 16 & 255) * 299 + (rgb >> 8 & 255) * 587 + (rgb & 255) * 114) / 1000; }

    public static void main(String[] args) throws Exception {
        for (String f : args) {
            BufferedImage img = ImageIO.read(new File(f));
            int w = img.getWidth(), h = img.getHeight();
            // Glass: light (lum 175..235), low saturation. Find the column band and row band where most pixels are glass.
            int x0 = -1, x1 = -1, y0 = -1, y1 = -1;
            int[] colCount = new int[w]; int[] rowCount = new int[h];
            for (int y = 0; y < h; y += 2) for (int x = w / 2; x < w; x += 2) {
                int c = img.getRGB(x, y); int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
                int l = lum(c), sat = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                if (l > 175 && l < 240 && sat < 14) { colCount[x]++; rowCount[y]++; }
            }
            int maxC = 0; for (int v : colCount) maxC = Math.max(maxC, v);
            for (int x = 0; x < w; x++) if (colCount[x] > maxC * 0.6) { if (x0 < 0) x0 = x; x1 = x; }
            int maxR = 0; for (int v : rowCount) maxR = Math.max(maxR, v);
            for (int y = 0; y < h; y++) if (rowCount[y] > maxR * 0.5) { if (y0 < 0) y0 = y; y1 = y; }
            if (x0 < 0 || y0 < 0) { System.out.println(f + ": window not found"); continue; }
            int ix0 = x0 + (x1 - x0) / 20, ix1 = x1 - (x1 - x0) / 20;
            int band = 0, best = 0;
            for (int y = y0 + (y1 - y0) / 40; y <= y1 - (y1 - y0) / 40; y++) {
                boolean blank = true;
                for (int x = ix0; x <= ix1 && blank; x += 2) if (lum(img.getRGB(x, y)) < 150) blank = false;
                band = blank ? band + 1 : 0; best = Math.max(best, band);
            }
            System.out.printf("%s: window %dx%d at (%d,%d), biggest blank band %d px = %.0f %%%n", new File(f).getName(), x1 - x0, y1 - y0, x0, y0, best, 100.0 * best / (y1 - y0));
        }
    }
}
