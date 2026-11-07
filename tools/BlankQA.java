// Short-page blank, QA's unified method (2026-11-05): blank OUTSIDE the content block, top + bottom, as a share of
// the window height, measured per column on the (trapezoid) window and merged. Also the biggest inner blank band.
//   java tools/BlankQA.java shot.png ...
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;

public class BlankQA {
    static int lum(int c) { return ((c >> 16 & 255) * 299 + (c >> 8 & 255) * 587 + (c & 255) * 114) / 1000; }
    static boolean glass(int c) { int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255; int l = lum(c); return l > 170 && l < 240 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 16; }

    static int[] edges(BufferedImage img, int x) {
        int bestS = -1, bestE = -1, s = -1;
        for (int y = 600; y < Math.min(img.getHeight(), 2300); y++) {
            boolean g = glass(img.getRGB(x, y));
            if (g && s < 0) s = y;
            if ((!g || y == 2299) && s >= 0) { if (y - s > bestE - bestS) { bestS = s; bestE = y; } s = -1; }
        }
        return bestE - bestS > 200 ? new int[]{bestS, bestE} : null;
    }

    public static void main(String[] a) throws Exception {
        for (String f : a) {
            BufferedImage img = ImageIO.read(new File(f));
            int w = img.getWidth(), h = img.getHeight();
            // Window columns: many glass pixels in the right half.
            int[] cnt = new int[w]; int max = 0;
            for (int x = w / 2; x < w; x++) { for (int y = 0; y < h; y += 2) if (glass(img.getRGB(x, y))) cnt[x]++; max = Math.max(max, cnt[x]); }
            int x0 = -1, x1 = -1;
            for (int x = 0; x < w; x++) if (cnt[x] > max * 0.6) { if (x0 < 0) x0 = x; x1 = x; }
            int span = x1 - x0, cx0 = x0 + span / 12, cx1 = x1 - span / 12;
            double topMin = 1, botMax = 0; int cols = 0;
            boolean[] contentFrac = new boolean[1000];
            // Window top / bottom: the longest pure-glass run in a column just inside the left and the right edge
            // (the padding strip has no content), interpolated across like QA's trapezoid.
            int[] l = edges(img, x0 + 12), r = edges(img, x1 - 12);
            if (l == null || r == null) { System.out.println(f + ": window edges not found"); continue; }
            for (int x = cx0; x <= cx1; x += 2) {
                double u = (double) (x - (x0 + 12)) / ((x1 - 12) - (x0 + 12));
                int bestS = (int) Math.round(l[0] + u * (r[0] - l[0])), bestE = (int) Math.round(l[1] + u * (r[1] - l[1]));
                int H = bestE - bestS, inset = (int) (H * 0.03); int t = bestS + inset, b = bestE - inset, n = b - t;
                int[] ls = new int[n]; for (int i = 0; i < n; i++) ls[i] = lum(img.getRGB(x, t + i));
                int first = -1, last = -1;
                for (int i = 0; i < n; i++) {
                    int lo = Math.max(0, i - 30), hi = Math.min(n, i + 31);
                    int[] win = Arrays.copyOfRange(ls, lo, hi); Arrays.sort(win);
                    if (Math.abs(ls[i] - win[win.length / 2]) > 18) { if (first < 0) first = i; last = i; contentFrac[(int) (999.0 * (i + inset) / H)] = true; }
                }
                cols++;
                if (first < 0) continue;
                topMin = Math.min(topMin, (double) (first + inset) / H);
                botMax = Math.max(botMax, (double) (last + inset) / H);
            }
            int band = 0, inner = 0; boolean started = false;
            int lastIdx = (int) (botMax * 999);
            for (int i = (int) (topMin * 999); i <= lastIdx; i++) { if (contentFrac[i]) { started = true; inner = Math.max(inner, band); band = 0; } else if (started) band++; }
            System.out.printf("%s: top %.1f%% + bottom %.1f%% = %.1f%%, inner band %.1f%% (cols %d)%n", new File(f).getName(), 100 * topMin, 100 * (1 - botMax), 100 * (topMin + 1 - botMax), inner / 10.0, cols);
        }
    }
}
