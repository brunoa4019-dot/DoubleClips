package com.vanvatcorporation.doubleclips.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Editor IA: reads the "language" of a reference edit from small frames sampled at a fixed rate: where it cuts, where
 * it flashes, where it fades to black / white, where the picture shakes or zooms. Pure Java: the frames are given as
 * per-frame features (luma histogram, brightness, motion), sampled on Android by {@code ReferenceFrameSampler}.
 */
public final class ReferenceAnalyzer {

    public static final int HIST_BINS = 16;

    /** Per-frame features of the reference video. */
    public static final class Frames {
        public float fps;
        /** [frame][HIST_BINS], each row sums to 1. */
        public float[][] hist;
        /** Mean luma 0..1. */
        public float[] brightness;
        /** Mean absolute luma difference to the previous frame, 0..1 (0 for the first frame). */
        public float[] motion;
        /** Mean saturation 0..1. */
        public float[] saturation;
        /** Standard deviation of luma 0..1 (low = flat picture: a blank, a fade); optional. */
        public float[] contrast;
        /** Fraction of strongly green pixels 0..1 (green screen); optional. */
        public float[] green;
        /** Tiny 8x8 RGB thumbnail per frame (192 values 0..1), the main cue for cuts; optional. */
        public float[][] thumb;
        /** 64x64 grey picture per frame (0..1), to measure camera motion; optional. */
        public float[][] luma;
    }

    public static final int THUMB = 8;

    /** One shot of the reference, with what happens in it. */
    public static final class RefShot {
        public float start, end;
        /** Effect id to copy (flash / white-out / camera-shake / warp-zoom / ...) or null. */
        public String effect;
        public float effectStrength = 1f;
        /** Mean picture change inside the shot (camera movement), 0..1. */
        public float motion;
        /**
         * The camera work of the shot, sampled at the reference's frame rate from the shot start: zoom (1 = as at the start),
         * pan x / y (fractions of the frame), and brightness relative to the shot's average (1 = average). Null if unknown.
         */
        public float[] camZoom, camX, camY, light;
        public float camFps;
        /** Average brightness of the shot 0..1 (to pick a source scene of the same mood). */
        public float brightness;
        /** Transition into the next shot, or null for a hard cut. */
        public String transitionOut;
        public float transitionDuration;
    }

    /** What was learned from the reference. */
    public static final class Pattern {
        public final List<RefShot> shots = new ArrayList<>();
        public float duration;
        /** Look of the reference, for colour matching: average brightness and saturation, 0..1. */
        public float avgBrightness, avgSaturation;
    }

    private ReferenceAnalyzer() { }

    public static Pattern analyze(Frames f) {
        Pattern p = new Pattern();
        int n = f.brightness.length;
        p.duration = n / f.fps;
        if (n < 3) {
            RefShot s = new RefShot();
            s.start = 0; s.end = p.duration;
            p.shots.add(s);
            return p;
        }

        // how different each frame is from the previous one (0..1): layout/colour (thumbnail) + tones (histogram)
        float[] d = new float[n];
        for (int i = 1; i < n; i++) d[i] = frameDistance(f, i - 1, i);
        float med = median(d), mad = median(absDev(d, med));
        // fast edits move the camera a lot, so a cut is a clear peak above the motion around it
        float cutThr = f.thumb != null ? Math.min(0.44f, Math.max(0.34f, med + 3f * mad)) : Math.max(0.28f, med + 5f * mad);

        // ---- cuts: a frame very different from the last one, not a single-frame flash ----
        // blur transitions spread a cut over several frames: compare frames ~0.25 s apart too
        int sw = Math.max(1, Math.round(f.fps * 0.125f));
        float[] dw = new float[n];
        for (int i = sw; i + sw < n; i++) dw[i] = frameDistance(f, i - sw, i + sw);
        boolean[] cut = new boolean[n];
        int minGap = Math.max(1, Math.round(f.fps * 0.25f));
        int last = -minGap;
        for (int i = 1; i < n; i++) {
            if (d[i] < cutThr || i - last < minGap) continue;
            boolean localMax = true;
            for (int k = Math.max(1, i - 2); k <= Math.min(n - 1, i + 2); k++) if (d[k] > d[i]) localMax = false;
            if (!localMax) continue;
            // a flash goes white and comes back: not a cut if the picture after the flash is the one from before it
            if (isFlashFrame(f, i) && frameDistance(f, i - 1, Math.min(n - 1, i + 2)) < cutThr) continue;
            cut[i] = true;
            last = i;
        }
        if (f.thumb != null) {
            for (int i = sw; i + sw < n; i++) {
                if (dw[i] < 0.45f) continue;
                boolean localMax = true;
                for (int k = Math.max(0, i - 2 * sw); k <= Math.min(n - 1, i + 2 * sw); k++) if (dw[k] > dw[i]) localMax = false;
                if (!localMax) continue;
                boolean near = false;
                for (int k = Math.max(0, i - minGap); k <= Math.min(n - 1, i + minGap); k++) if (cut[k]) near = true;
                if (near) continue;
                // the cut itself is the biggest single-frame jump inside the blurred span
                int best = i;
                for (int k = Math.max(1, i - sw); k <= Math.min(n - 1, i + sw); k++) if (d[k] > d[best]) best = k;
                if (d[best] < 0.1f) continue; // smooth camera move, not a cut
                cut[best] = true;
            }
        }

        // ---- shots ----
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 1; i < n; i++) if (cut[i]) starts.add(i);
        float bSum = 0, sSum = 0;
        for (int i = 0; i < n; i++) { bSum += f.brightness[i]; sSum += f.saturation == null ? 0 : f.saturation[i]; }
        p.avgBrightness = bSum / n;
        p.avgSaturation = sSum / n;
        float motionMed = median(f.motion);

        for (int k = 0; k < starts.size(); k++) {
            int a = starts.get(k), b = k + 1 < starts.size() ? starts.get(k + 1) : n;
            RefShot s = new RefShot();
            s.start = a / f.fps;
            s.end = b / f.fps;

            // flash: brightness jumps well above the shot's level in its first frames
            float base = medianRange(f.brightness, a, b);
            float peak = 0;
            int head = Math.min(b, a + Math.max(2, Math.round(f.fps * 0.25f)));
            for (int i = a; i < head; i++) peak = Math.max(peak, f.brightness[i]);
            boolean flash = peak > 0.8f && peak - base > 0.25f;
            for (int i = a; i < b && !flash; i++) if (isFlashFrame(f, i)) flash = true;

            // camera movement inside the shot (no cut): the edit moves the picture, copy it as zoom / shake
            float m = meanRange(f.motion, a + 1, b);
            s.motion = m;
            boolean shaky = (m > Math.max(0.06f, motionMed * 2.2f) || m > 0.045f) && (b - a) >= 3;

            if (flash) {
                s.effect = peak > 0.93f ? "white-out" : "flash";
                s.effectStrength = Math.min(2f, 0.8f + (peak - base) * 2f);
            } else if (shaky) {
                s.effect = m > 0.16f ? "camera-shake" : "slow-push";
                s.effectStrength = Math.min(2.5f, 0.6f + m * 8f);
            }
            s.brightness = meanRange(f.brightness, a, b);
            if (f.luma != null && f.luma.length >= b) measureCamera(f, s, a, b);
            p.shots.add(s);
        }

        // ---- transitions: read the frames around every cut (blur, flash, direction of the movement) ----
        if (f.luma != null) {
            float[] sharp = new float[n];
            for (int i = 0; i < n; i++) sharp[i] = f.luma[i] == null ? 0 : sharpness(f.luma[i]);
            for (int k = 0; k + 1 < p.shots.size(); k++) detectTransition(f, p, k, sharp);
        } else {
            for (int k = 0; k + 1 < p.shots.size(); k++) {
                int c = Math.round(p.shots.get(k).end * f.fps);
                float before = meanRange(f.motion, c - 3, c), after = meanRange(f.motion, c + 1, c + 4);
                if (before > 0.11f && after > 0.11f) {
                    RefShot s = p.shots.get(k);
                    s.transitionOut = "zoom-blur";
                    s.transitionDuration = 0.3f;
                }
            }
        }

        // ---- fades: brightness goes to black / white around a cut ----
        for (int k = 0; k + 1 < p.shots.size(); k++) {
            int cutFrame = Math.round(p.shots.get(k).end * f.fps);
            int w = Math.max(2, Math.round(f.fps * 0.25f));
            float minB = 1, maxB = 0;
            for (int i = Math.max(0, cutFrame - w); i < Math.min(n, cutFrame + w); i++) {
                minB = Math.min(minB, f.brightness[i]);
                maxB = Math.max(maxB, f.brightness[i]);
            }
            float around = (medianRange(f.brightness, Math.max(0, cutFrame - 3 * w), Math.max(1, cutFrame - w))
                    + medianRange(f.brightness, Math.min(n - 1, cutFrame + w), Math.min(n, cutFrame + 3 * w))) / 2f;
            RefShot s = p.shots.get(k);
            if (minB < 0.06f && around > 0.15f) { s.transitionOut = "fadeblack"; s.transitionDuration = 0.35f; }
            else if (maxB > 0.95f && around < 0.8f && !"white-out".equals(p.shots.get(k + 1).effect)) { s.transitionOut = "fadewhite"; s.transitionDuration = 0.3f; }
        }
        return p;
    }

    /** Follows the camera through the shot: accumulated zoom and pan, plus the light curve. */
    private static void measureCamera(Frames f, RefShot s, int a, int b) {
        int len = b - a;
        s.camFps = f.fps;
        s.camZoom = new float[len];
        s.camX = new float[len];
        s.camY = new float[len];
        s.light = new float[len];
        float z = 1, x = 0, y = 0;
        float avg = Math.max(0.02f, meanRange(f.brightness, a, b));
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                float[] la = f.luma[a + i - 1], lb = f.luma[a + i];
                float[] m = la == null || lb == null ? new float[]{1, 0, 0} : MotionEstimator.estimate(la, lb);
                z *= m[0];
                x += m[1] * z;
                y += m[2] * z;
                // keep it inside what can be copied without showing borders
                z = Math.max(0.7f, Math.min(1.8f, z));
            }
            s.camZoom[i] = z;
            s.camX[i] = x;
            s.camY[i] = y;
            s.light[i] = f.brightness[a + i] / avg;
        }
        // Frame-to-frame estimates are noisy (fire, particles, blur): copied raw they make the picture shake for no
        // reason. Keep only the smooth camera move: a quadratic curve through each track (start / speed / ease).
        fitSmooth(s.camZoom, 1f);
        fitSmooth(s.camX, 0f);
        fitSmooth(s.camY, 0f);
        // ignore moves too small to be intentional
        float zr = 0, xr = 0, yr = 0;
        for (int i = 0; i < len; i++) {
            zr = Math.max(zr, Math.abs(s.camZoom[i] - 1f));
            xr = Math.max(xr, Math.abs(s.camX[i]));
            yr = Math.max(yr, Math.abs(s.camY[i]));
        }
        if (zr < 0.03f) java.util.Arrays.fill(s.camZoom, 1f);
        if (xr < 0.025f) java.util.Arrays.fill(s.camX, 0f);
        if (yr < 0.025f) java.util.Arrays.fill(s.camY, 0f);
    }

    /** Least-squares quadratic through v (x = index), starting exactly at {@code start}. In place. */
    static void fitSmooth(float[] v, float start) {
        int n = v.length;
        if (n < 3) return;
        // fit v - start = b*t + c*t^2 (t in 0..1)
        double s11 = 0, s12 = 0, s22 = 0, r1 = 0, r2 = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) (n - 1), y = v[i] - start;
            s11 += t * t; s12 += t * t * t; s22 += t * t * t * t; r1 += t * y; r2 += t * t * y;
        }
        double det = s11 * s22 - s12 * s12;
        if (Math.abs(det) < 1e-12) return;
        double b = (r1 * s22 - r2 * s12) / det, c = (s11 * r2 - s12 * r1) / det;
        for (int i = 0; i < n; i++) {
            double t = i / (double) (n - 1);
            v[i] = (float) (start + b * t + c * t * t);
        }
    }

    /** Mean |horizontal| and |vertical| gradient of a 64x64 grey frame. */
    static float[] gradients(float[] l) {
        int n = MotionEstimator.SIZE;
        double gx = 0, gy = 0;
        for (int y = 1; y < n - 1; y++) for (int x = 1; x < n - 1; x++) {
            int i = y * n + x;
            gx += Math.abs(l[i + 1] - l[i - 1]);
            gy += Math.abs(l[i + n] - l[i - n]);
        }
        return new float[]{(float) gx, (float) gy};
    }

    /** Mean absolute Laplacian of a 64x64 grey frame: high = sharp, low = blurred. */
    static float sharpness(float[] l) {
        int n = MotionEstimator.SIZE;
        double s = 0;
        int c = 0;
        for (int y = 1; y < n - 1; y++) {
            for (int x = 1; x < n - 1; x++) {
                int i = y * n + x;
                s += Math.abs(4 * l[i] - l[i - 1] - l[i + 1] - l[i - n] - l[i + n]);
                c++;
            }
        }
        return (float) (s / c);
    }

    /**
     * Looks at the frames just before and after the cut between shot k and k+1. Blurred frames around the cut = a blur
     * transition; their span gives its length. A brightness peak = it flashes. Sideways movement = a whip (left/right),
     * otherwise a zoom blur.
     */
    private static void detectTransition(Frames f, Pattern p, int k, float[] sharp) {
        int n = sharp.length;
        RefShot a = p.shots.get(k), b = p.shots.get(k + 1);
        int cut = Math.round(b.start * f.fps);
        int aStart = Math.round(a.start * f.fps), bEnd = Math.min(n, Math.round(b.end * f.fps));
        float refA = medianRange(sharp, aStart, cut), refB = medianRange(sharp, cut, bEnd);
        int maxSpan = Math.max(1, Math.round(f.fps * 0.35f));
        int before = 0, after = 0;
        for (int i = cut - 1; i >= Math.max(aStart, cut - maxSpan); i--) {
            if (refA > 0 && sharp[i] < refA * 0.62f) before++; else break;
        }
        for (int i = cut; i < Math.min(bEnd, cut + maxSpan); i++) {
            if (refB > 0 && sharp[i] < refB * 0.62f) after++; else break;
        }
        // brightness peak right at the cut
        float lightAround = Math.max(medianRange(f.brightness, aStart, cut), medianRange(f.brightness, cut, bEnd));
        float peak = 0;
        for (int i = Math.max(0, cut - 2); i < Math.min(n, cut + 3); i++) peak = Math.max(peak, f.brightness[i]);
        boolean flash = peak > lightAround * 1.3f && peak > 0.45f;
        if (before + after < 2 && !flash) return; // a plain hard cut

        // direction of the movement inside the blurred frames
        float pan = 0, zoom = 0;
        for (int i = Math.max(aStart + 1, cut - before - 1); i < Math.min(bEnd, cut + after + 1); i++) {
            if (i == cut || f.luma[i - 1] == null || f.luma[i] == null) continue;
            float[] m = MotionEstimator.estimate(f.luma[i - 1], f.luma[i]);
            pan += m[1];
            zoom += Math.abs(m[0] - 1f);
        }
        // streak direction: a sideways motion blur wipes out horizontal detail but keeps vertical detail
        float gx = 0, gy = 0;
        for (int i = Math.max(0, cut - before); i < Math.min(n, cut + after); i++) {
            if (f.luma[i] == null) continue;
            float[] g = gradients(f.luma[i]);
            gx += g[0]; gy += g[1];
        }
        boolean sideways = gy > 0 && gx / gy < 0.75f;
        String style;
        if (before + after < 2) style = "flash-blur";
        else if (sideways || (Math.abs(pan) > zoom * 1.2f && Math.abs(pan) > 0.04f)) style = pan < 0 ? "whip-left" : "whip-right";
        else style = flash ? "flash-blur" : "zoom-blur";
        a.transitionOut = style;
        float span = 2f * Math.max(Math.max(before, after), 1) + 1f;
        a.transitionDuration = Math.max(0.12f, Math.min(0.6f, span / f.fps));
    }

    /** True when frame i is much brighter than both neighbours (a one or two frame white flash). */
    private static boolean isFlashFrame(Frames f, int i) {
        int n = f.brightness.length;
        if (i < 1 || i + 1 >= n) return false;
        float b = f.brightness[i];
        float before = f.brightness[i - 1];
        float after = f.brightness[Math.min(n - 1, i + 2)];
        return b > 0.8f && b - before > 0.3f && b - after > 0.3f;
    }

    private static float histDistance(Frames f, int a, int b) {
        float s = 0;
        for (int k = 0; k < HIST_BINS; k++) s += Math.abs(f.hist[a][k] - f.hist[b][k]);
        return s * 0.5f;
    }

    static float frameDistance(Frames f, int a, int b) {
        float h = histDistance(f, a, b);
        if (f.thumb == null) return h;
        float[] x = f.thumb[a], y = f.thumb[b];
        float t = 0;
        for (int k = 0; k < x.length; k++) t += Math.abs(x[k] - y[k]);
        t /= x.length;
        return Math.min(1f, 0.35f * h + 0.65f * t * 2.5f);
    }

    private static float median(float[] x) {
        if (x.length == 0) return 0;
        float[] c = x.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    private static float[] absDev(float[] x, float m) {
        float[] o = new float[x.length];
        for (int i = 0; i < x.length; i++) o[i] = Math.abs(x[i] - m);
        return o;
    }

    private static float medianRange(float[] x, int a, int b) {
        a = Math.max(0, a); b = Math.min(x.length, b);
        if (b <= a) return a < x.length ? x[a] : 0;
        return median(Arrays.copyOfRange(x, a, b));
    }

    private static float meanRange(float[] x, int a, int b) {
        a = Math.max(0, a); b = Math.min(x.length, b);
        if (b <= a) return 0;
        float s = 0;
        for (int i = a; i < b; i++) s += x[i];
        return s / (b - a);
    }
}
