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
                s.effect = m > 0.16f ? "camera-shake" : (k % 3 == 2 ? "slow-push" : "warp-zoom");
                s.effectStrength = Math.min(2.5f, 0.6f + m * 8f);
            }
            p.shots.add(s);
        }

        // ---- whip / blur cuts: the picture is moving fast on both sides of the cut ----
        for (int k = 0; k + 1 < p.shots.size(); k++) {
            int c = Math.round(p.shots.get(k).end * f.fps);
            float before = meanRange(f.motion, c - 3, c), after = meanRange(f.motion, c + 1, c + 4);
            if (before > 0.11f && after > 0.11f) {
                RefShot s = p.shots.get(k);
                s.transitionOut = before + after > 0.32f ? "glitchblur" : "hblur";
                s.transitionDuration = 0.22f;
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
