package com.vanvatcorporation.doubleclips.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Editor IA: finds the beats of a song from its mono PCM samples. Pure Java (no Android), so it runs anywhere.
 *
 * <p>Steps: spectral-flux onset envelope -> tempo by autocorrelation (weighted towards ~120 BPM) -> beat tracking by
 * dynamic programming (Ellis 2007) -> per-beat strength and loudness, used by {@link AutoEditPlanner} to decide where to
 * cut and where to hit with effects.</p>
 */
public final class BeatDetector {

    public static final int FRAME = 1024;
    public static final int HOP = 512;

    /** What the analysis found. Times in seconds. */
    public static final class Result {
        public float bpm;
        public float duration;
        /** Beat times, ascending. */
        public float[] beats = new float[0];
        /** Onset strength at each beat, 0..1 (1 = the hardest hit of the song). */
        public float[] beatStrength = new float[0];
        /** Loudness around each beat, 0..1 (1 = the loudest part of the song), smoothed over ~2 bars. */
        public float[] beatEnergy = new float[0];
        /** Onset envelope (0..1), one value per hop; used to line up two recordings of the same song. */
        public float[] onsetEnvelope = new float[0];
        /** Values of {@link #onsetEnvelope} per second. */
        public float envelopeRate;
    }

    private BeatDetector() { }

    public static Result analyze(float[] mono, int sampleRate) {
        Result r = new Result();
        r.duration = mono.length / (float) sampleRate;
        if (mono.length < FRAME * 4) return r;

        float frameRate = sampleRate / (float) HOP;
        int frames = (mono.length - FRAME) / HOP + 1;

        // ---- onset envelope (log-magnitude spectral flux) and frame loudness ----
        float[] onset = new float[frames];
        float[] rms = new float[frames];
        float[] window = new float[FRAME];
        for (int i = 0; i < FRAME; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FRAME - 1)));
        float[] re = new float[FRAME], im = new float[FRAME];
        int bins = FRAME / 2;
        float[] prev = new float[bins];
        for (int f = 0; f < frames; f++) {
            int off = f * HOP;
            double sq = 0;
            for (int i = 0; i < FRAME; i++) {
                float s = mono[off + i];
                sq += s * s;
                re[i] = s * window[i];
                im[i] = 0;
            }
            rms[f] = (float) Math.sqrt(sq / FRAME);
            fft(re, im);
            float flux = 0;
            for (int k = 1; k < bins; k++) {
                float mag = (float) Math.log1p(100.0 * Math.hypot(re[k], im[k]));
                float d = mag - prev[k];
                if (d > 0) flux += d;
                prev[k] = mag;
            }
            onset[f] = flux;
        }
        onset[0] = 0;
        // remove the slow trend (local mean over ~0.5 s), keep the peaks
        int half = Math.max(1, Math.round(frameRate * 0.25f));
        float[] env = new float[frames];
        double run = 0;
        int lo = 0, hi = -1;
        for (int f = 0; f < frames; f++) {
            while (hi < Math.min(frames - 1, f + half)) run += onset[++hi];
            while (lo < f - half) run -= onset[lo++];
            float mean = (float) (run / (hi - lo + 1));
            env[f] = Math.max(0, onset[f] - mean);
        }
        normalize(env);
        r.onsetEnvelope = env;
        r.envelopeRate = frameRate;

        // ---- tempo ----
        float period = estimatePeriod(env, frameRate); // in frames
        r.bpm = 60f * frameRate / period;

        // ---- beat tracking (dynamic programming) ----
        int[] beatFrames = trackBeats(env, period);
        int n = beatFrames.length;
        r.beats = new float[n];
        r.beatStrength = new float[n];
        r.beatEnergy = new float[n];
        float[] loud = smooth(rms, Math.max(1, Math.round(period * 4))); // ~1 bar
        normalize(loud);
        for (int i = 0; i < n; i++) {
            int bf = beatFrames[i];
            r.beats[i] = (bf * HOP + FRAME / 2f) / sampleRate;
            float peak = 0;
            for (int f = Math.max(0, bf - 2); f <= Math.min(frames - 1, bf + 2); f++) peak = Math.max(peak, env[f]);
            r.beatStrength[i] = peak;
            r.beatEnergy[i] = loud[bf];
        }
        normalize(r.beatStrength);
        r.beatEnergy = smoothArray(r.beatEnergy, 4);
        normalize(r.beatEnergy);
        return r;
    }

    // ------------------------------------------------------------------------------------------

    private static float estimatePeriod(float[] env, float frameRate) {
        int minLag = Math.max(2, Math.round(frameRate * 60f / 200f));
        int maxLag = Math.min(env.length - 1, Math.round(frameRate * 60f / 60f));
        double best = -1;
        int bestLag = Math.round(frameRate * 0.5f);
        double[] ac = new double[maxLag + 2];
        for (int lag = minLag; lag <= maxLag; lag++) {
            double s = 0;
            for (int t = lag; t < env.length; t++) s += env[t] * env[t - lag];
            ac[lag] = s / (env.length - lag);
        }
        for (int lag = minLag; lag <= maxLag; lag++) {
            // prefer tempi near 120 BPM (log-gaussian), and reward lags whose double also correlates
            double bpm = 60.0 * frameRate / lag;
            double w = Math.exp(-0.5 * Math.pow(Math.log(bpm / 120.0) / Math.log(2) / 0.9, 2));
            double s = ac[lag] + (2 * lag <= maxLag ? 0.5 * ac[2 * lag] : 0);
            double score = s * w;
            if (score > best) { best = score; bestLag = lag; }
        }
        // parabolic refinement
        if (bestLag > minLag && bestLag < maxLag) {
            double a = ac[bestLag - 1], b = ac[bestLag], c = ac[bestLag + 1];
            double den = a - 2 * b + c;
            if (den < 0) return (float) (bestLag + 0.5 * (a - c) / den);
        }
        return bestLag;
    }

    private static int[] trackBeats(float[] env, float period) {
        int n = env.length;
        double tightness = 100;
        double[] score = new double[n];
        int[] back = new int[n];
        int pMin = Math.max(1, Math.round(period * 0.5f));
        int pMax = Math.max(pMin + 1, Math.round(period * 2f));
        for (int t = 0; t < n; t++) {
            double bestPrev = 0;
            int arg = -1;
            for (int p = t - pMax; p <= t - pMin; p++) {
                if (p < 0) continue;
                double d = Math.log((t - p) / period);
                double s = score[p] - tightness * d * d;
                if (arg < 0 || s > bestPrev) { bestPrev = s; arg = p; }
            }
            score[t] = env[t] + (arg >= 0 ? bestPrev : 0);
            back[t] = arg;
        }
        // end on the best-scoring frame of the last period
        int start = Math.max(0, n - Math.round(period));
        int end = start;
        for (int t = start; t < n; t++) if (score[t] > score[end]) end = t;
        List<Integer> beats = new ArrayList<>();
        for (int t = end; t >= 0; t = back[t]) {
            beats.add(t);
            if (back[t] < 0) break;
        }
        int[] out = new int[beats.size()];
        for (int i = 0; i < out.length; i++) out[i] = beats.get(out.length - 1 - i);
        return out;
    }

    private static float[] smooth(float[] x, int radius) {
        float[] y = new float[x.length];
        double run = 0;
        int lo = 0, hi = -1;
        for (int i = 0; i < x.length; i++) {
            while (hi < Math.min(x.length - 1, i + radius)) run += x[++hi];
            while (lo < i - radius) run -= x[lo++];
            y[i] = (float) (run / (hi - lo + 1));
        }
        return y;
    }

    private static float[] smoothArray(float[] x, int radius) {
        return x.length == 0 ? x : smooth(x, radius);
    }

    private static void normalize(float[] x) {
        float max = 0, min = Float.MAX_VALUE;
        for (float v : x) { max = Math.max(max, v); min = Math.min(min, v); }
        if (x.length == 0) return;
        float range = max - min;
        if (range <= 1e-9f) { Arrays.fill(x, max > 0 ? 1f : 0f); return; }
        for (int i = 0; i < x.length; i++) x[i] = (x[i] - min) / range;
    }

    /** In-place iterative radix-2 FFT; length must be a power of two. */
    static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    float xr = re[b] * cr - im[b] * ci;
                    float xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    float ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }
}
