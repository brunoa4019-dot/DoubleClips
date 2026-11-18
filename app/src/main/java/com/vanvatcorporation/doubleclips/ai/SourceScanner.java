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
            boolean ok = b > 0.09f && b < 0.88f;
            if (f.contrast != null) {
                ok &= f.contrast[i] > 0.07f;
                ok &= !(b > 0.65f && f.contrast[i] < 0.13f); // washed out: a white flash / fade of the source
            }
            if (f.green != null) ok &= f.green[i] < 0.2f;
            good[i] = ok;
        }
        // frozen pictures (title cards, "link in the comments" screens, still panels): skip runs of 1 s or more
        int still = Math.round(f.fps * 1.0f);
        for (int i = 1, run = 0; i <= n; i++) {
            boolean frozen = i < n && f.motion[i] < 0.005f;
            if (frozen) run++;
            if (!frozen) {
                if (run >= still) for (int k = i - run - 1; k < i; k++) if (k >= 0) good[k] = false;
                run = 0;
            }
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
}
