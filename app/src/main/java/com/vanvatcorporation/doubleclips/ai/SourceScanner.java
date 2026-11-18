package com.vanvatcorporation.doubleclips.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor IA: finds the parts of a source clip that are worth using: scenes of the clip, minus black / white /
 * flat frames (fades, blanks, title cards on black) and green screen. Pure Java, from {@link ReferenceAnalyzer.Frames}.
 */
public final class SourceScanner {

    private SourceScanner() { }

    /** Good ranges [start, end] in seconds, each inside one scene of the clip. Empty = nothing good found. */
    public static float[][] goodRanges(ReferenceAnalyzer.Frames f) {
        int n = f.brightness.length;
        boolean[] good = new boolean[n];
        for (int i = 0; i < n; i++) {
            float b = f.brightness[i];
            boolean ok = b > 0.12f && b < 0.88f;
            if (f.contrast != null) {
                ok &= f.contrast[i] > 0.07f;
                ok &= !(b > 0.65f && f.contrast[i] < 0.13f); // washed out: a white flash / fade of the source
            }
            if (f.green != null) ok &= f.green[i] < 0.2f;
            good[i] = ok;
        }
        // frozen pictures (title cards, "use my clips" text, still panels): the picture one second later is the same.
        // (Slow motion / twixtor clips barely move frame to frame, so a 1-frame test would throw them away.)
        int sec = Math.max(1, Math.round(f.fps));
        boolean[] frozen = new boolean[n];
        for (int i = 0; i < n; i++) {
            int j = Math.min(n - 1, i + sec);
            float dd = ReferenceAnalyzer.frameDistance(f, i, j);
            frozen[i] = j > i && dd < 0.005f;
        }
        for (int i = 0, run = 0; i <= n; i++) {
            if (i < n && frozen[i]) { run++; continue; }
            if (run >= sec) for (int k = i - run; k < Math.min(n, i + sec); k++) if (k >= 0) good[k] = false; // + the second it covers
            // right after a long frozen intro the text usually fades out over the first scene: skip 2 more seconds
            if (run >= 3 * sec) for (int k = i + sec; k < Math.min(n, i + 3 * sec); k++) good[k] = false;
            run = 0;
        }
        ReferenceAnalyzer.Pattern scenes = ReferenceAnalyzer.analyze(f);
        float edge = 0.12f;
        float minLen = 0.5f;
        List<float[]> out = new ArrayList<>();
        for (ReferenceAnalyzer.RefShot sc : scenes.shots) {
            int a = Math.round((sc.start + edge) * f.fps), b = Math.round((sc.end - edge) * f.fps);
            int runStart = -1;
            for (int i = Math.max(0, a); i <= Math.min(n, b); i++) {
                boolean g = i < Math.min(n, b) && good[i];
                if (g && runStart < 0) runStart = i;
                if (!g && runStart >= 0) {
                    float s = runStart / f.fps, e = i / f.fps;
                    if (e - s >= minLen) out.add(new float[]{s, e});
                    runStart = -1;
                }
            }
        }
        return out.toArray(new float[0][]);
    }

    /** Average brightness of each range. */
    public static float[] rangeLight(ReferenceAnalyzer.Frames f, float[][] ranges) {
        float[] out = new float[ranges.length];
        for (int k = 0; k < ranges.length; k++) {
            int a = Math.max(0, Math.round(ranges[k][0] * f.fps)), b = Math.min(f.brightness.length, Math.round(ranges[k][1] * f.fps));
            float s = 0; int c = 0;
            for (int i = a; i < b; i++) { s += f.brightness[i]; c++; }
            out[k] = c == 0 ? 0.4f : s / c;
        }
        return out;
    }

    /** Average {brightness, saturation} of the clip over its good ranges (whole clip when none). */
    public static float[] averageLook(ReferenceAnalyzer.Frames f, float[][] ranges) {
        double b = 0, sa = 0;
        int c = 0;
        for (int i = 0; i < f.brightness.length; i++) {
            float t = i / f.fps;
            boolean in = ranges == null || ranges.length == 0;
            if (!in) for (float[] r : ranges) if (t >= r[0] && t <= r[1]) { in = true; break; }
            if (!in) continue;
            b += f.brightness[i];
            sa += f.saturation == null ? 0 : f.saturation[i];
            c++;
        }
        return c == 0 ? new float[]{0.4f, 0.3f} : new float[]{(float) (b / c), (float) (sa / c)};
    }

    /** Fills plan.saturation / plan.brightness so the clips take the reference's look (kept mild). */
    public static void matchLook(AutoEditPlanner.Plan plan, ReferenceAnalyzer.Pattern ref, float[] sourceLook) {
        float sat = sourceLook[1] < 0.03f ? 1f : ref.avgSaturation / sourceLook[1];
        plan.saturation = Math.max(0.7f, Math.min(1.7f, sat));
        plan.brightness = Math.max(-0.08f, Math.min(0.08f, (ref.avgBrightness - sourceLook[0]) * 0.4f));
    }

    /**
     * Colour of a clip in the editor's own terms (the shader works in YUV): mean luma Y, mean chroma strength C and the
     * red/blue balance R/B, over the given ranges (all frames when null). From the 8x8 thumbnails.
     */
    public static float[] colorStats(ReferenceAnalyzer.Frames f, float[][] ranges) {
        if (f.thumb == null) return null;
        double y = 0, c = 0, r = 0, b = 0;
        long n = 0;
        for (int i = 0; i < f.thumb.length; i++) {
            float t = i / f.fps;
            boolean in = ranges == null || ranges.length == 0;
            if (!in) for (float[] rg : ranges) if (t >= rg[0] && t <= rg[1]) { in = true; break; }
            if (!in || f.thumb[i] == null) continue;
            float[] th = f.thumb[i];
            for (int k = 0; k + 2 < th.length; k += 3) {
                float R = th[k], G = th[k + 1], B = th[k + 2];
                float Y = 0.299f * R + 0.587f * G + 0.114f * B;
                float U = -0.14713f * R - 0.28886f * G + 0.436f * B;
                float V = 0.615f * R - 0.51499f * G - 0.10001f * B;
                y += Y; c += Math.sqrt(U * U + V * V); r += R; b += B; n++;
            }
        }
        if (n == 0) return null;
        return new float[]{(float) (y / n), (float) (c / n), (float) ((r + 1e-3) / (b + 1e-3))};
    }

    /**
     * Gives the clips the reference's colours with the editor's own controls: brightness (luma, x10 units), saturation
     * (chroma) and temperature (red/blue balance, the shader's 6500 K +- 30% tint).
     */
    public static void matchColor(AutoEditPlanner.Plan plan, float[] ref, float[] src) {
        if (ref == null || src == null) return;
        plan.brightness = Math.max(-0.8f, Math.min(0.8f, (ref[0] - src[0]) * 10f * 0.5f)); // half way, gently: a big shift crushes the picture
        plan.saturation = src[1] < 0.01f ? 1f : Math.max(0.4f, Math.min(1.8f, ref[1] / src[1]));
        float q = ref[2] / Math.max(1e-3f, src[2]);
        float t = (q - 1f) / (0.3f * (q + 1f));
        t = Math.max(-0.6f, Math.min(0.6f, t));
        plan.temperature = 6500f + t * 6500f;
    }
}
