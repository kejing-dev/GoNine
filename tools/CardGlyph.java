// Measures the glyph height on the volume's cream cards in a screenshot (mustPass M5: ≥ 18 mm). The card is 40 mm
// tall in the world; card and glyphs lean back together, so glyph mm = ink rows / card rows × 40.
//   java tools/CardGlyph.java shot.png
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

public class CardGlyph {
    static boolean cream(int c) { int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255; return r > 225 && g > 220 && b > 205 && r - b >= 8 && r - b <= 30; }
    static int lum(int c) { return ((c >> 16 & 255) * 299 + (c >> 8 & 255) * 587 + (c & 255) * 114) / 1000; }

    public static void main(String[] a) throws Exception {
        BufferedImage img = ImageIO.read(new File(a[0]));
        int w = img.getWidth(), h = img.getHeight();
        boolean[][] seen = new boolean[h][w];
        // Cards are in the middle third of the frame, below the board.
        for (int y = h / 3; y < h * 3 / 4; y += 3) for (int x = 0; x < w * 3 / 4; x += 3) {
            if (seen[y][x] || !cream(img.getRGB(x, y))) continue;
            // Flood fill the cream region (step 1) to get its bounding box.
            int x0 = x, x1 = x, y0 = y, y1 = y, n = 0;
            Deque<int[]> st = new ArrayDeque<>(); st.push(new int[]{x, y}); seen[y][x] = true;
            while (!st.isEmpty()) {
                int[] p = st.pop(); n++;
                x0 = Math.min(x0, p[0]); x1 = Math.max(x1, p[0]); y0 = Math.min(y0, p[1]); y1 = Math.max(y1, p[1]);
                int[][] nb = {{1,0},{-1,0},{0,1},{0,-1}};
                for (int[] d : nb) { int nx = p[0] + d[0], ny = p[1] + d[1];
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h || seen[ny][nx]) continue;
                    if (cream(img.getRGB(nx, ny))) { seen[ny][nx] = true; st.push(new int[]{nx, ny}); } }
            }
            if (n < 2000 || x1 - x0 < 60 || y1 - y0 < 20 || y1 - y0 > 200) continue;
            // Ink rows inside the card (dark text), with a 2 px inset.
            int top = -1, bot = -1;
            for (int yy = y0 + 2; yy <= y1 - 2; yy++) {
                int ink = 0;
                for (int xx = x0 + 4; xx <= x1 - 4; xx++) if (lum(img.getRGB(xx, yy)) < 110) ink++;
                if (ink >= 2) { if (top < 0) top = yy; bot = yy; }
            }
            if (top < 0) continue;
            int cardPx = y1 - y0 + 1, inkPx = bot - top + 1;
            System.out.printf("card at x %d..%d y %d..%d: card %d px, ink %d px -> glyph %.1f mm (card = 40 mm)%n", x0, x1, y0, y1, cardPx, inkPx, 40.0 * inkPx / cardPx);
        }
    }
}
