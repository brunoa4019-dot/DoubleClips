package com.vanvatcorporation.doubleclips.ai;

/**
 * Editor IA: measures how the "camera" moves between two small grey frames: zoom and pan. Used to copy the camera work
 * of a reference edit (its push-ins, punch zooms, pans and shakes) onto other clips. Pure Java, brute force on 64x64.
 */
public final class MotionEstimator {

    public static final int SIZE = 64;
    private static final float[] SCALES = {0.94f, 0.96f, 0.975f, 0.99f, 1f, 1.01f, 1.025f, 1.04f, 1.06f};
    private static final int SHIFT = 5;

    private MotionEstimator() { }

    /**
     * Best {zoom, dx, dy} such that frame b ≈ frame a zoomed by zoom (around the centre) and moved by (dx, dy) in fractions
     * of the frame size. zoom > 1 = the camera moved in; dx > 0 = the picture moved right.
     */
    public static float[] estimate(float[] a, float[] b) {
        int n = SIZE;
        float c = (n - 1) / 2f;
        int margin = 10;
        double best = Double.MAX_VALUE;
        float bz = 1, bx = 0, by = 0;
        for (float z : SCALES) {
            for (int dy = -SHIFT; dy <= SHIFT; dy++) {
                for (int dx = -SHIFT; dx <= SHIFT; dx++) {
                    double sad = 0;
                    int cnt = 0;
                    for (int y = margin; y < n - margin; y += 2) {
                        for (int x = margin; x < n - margin; x += 2) {
                            // pixel (x,y) of b came from a at: centre + (p - centre - shift) / zoom
                            int sx = Math.round(c + (x - c - dx) / z);
                            int sy = Math.round(c + (y - c - dy) / z);
                            if (sx < 0 || sy < 0 || sx >= n || sy >= n) continue;
                            sad += Math.abs(b[y * n + x] - a[sy * n + sx]);
                            cnt++;
                        }
                    }
                    if (cnt == 0) continue;
                    sad /= cnt;
                    // tiny preference for "no motion" so flat pictures don't drift
                    sad += 0.0005 * (Math.abs(dx) + Math.abs(dy)) + 0.01 * Math.abs(z - 1);
                    if (sad < best) { best = sad; bz = z; bx = dx; by = dy; }
                }
            }
        }
        return new float[]{bz, bx / n, by / n};
    }
}
