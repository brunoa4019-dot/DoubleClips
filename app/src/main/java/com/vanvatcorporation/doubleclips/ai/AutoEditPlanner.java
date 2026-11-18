package com.vanvatcorporation.doubleclips.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Editor IA: turns the beats of a song and a set of video clips into an edit plan (which piece of which clip goes
 * where, with which effect and transition). Pure Java, no Android: the editor turns the plan into its own clips.
 *
 * <p>Ideas: cut on the beat, cut faster where the song is loud, hit the hardest beats with a flash / shake / glitch,
 * mark the big changes of the song (drops, breaks) with a transition, walk through each source clip instead of
 * repeating the same part.</p>
 */
public final class AutoEditPlanner {

    public enum Style {
        /** Fast cuts and many effects (anime edit, hype). */
        HYPE,
        /** Cuts every half bar / bar, effects only on the big hits. */
        BALANCED,
        /** Long shots, soft transitions, few effects. */
        CHILL
    }

    /** One video source (already copied into the project). */
    public static final class Source {
        public final String name;
        public final float duration;
        /** Usable parts [start, end] seconds (from {@link SourceScanner}); null = the whole clip. */
        public float[][] goodRanges;

        public Source(String name, float duration) {
            this.name = name;
            this.duration = duration;
        }

        float[][] ranges() {
            return goodRanges == null || goodRanges.length == 0 ? new float[][]{{0, duration}} : goodRanges;
        }

        float longest() {
            float m = 0;
            for (float[] r : ranges()) m = Math.max(m, r[1] - r[0]);
            return m;
        }
    }

    /**
     * Chooses which clip and which part of it fills each shot: never the same clip twice in a row (when there are
     * several), only usable parts, and moving forward through each part instead of repeating it.
     */
    static final class SourcePicker {
        private final List<Source> sources;
        private final Random rnd;
        private final int[] rangeIdx;
        private final float[] cursor;
        private int last = -1;

        SourcePicker(List<Source> sources, Random rnd) {
            this.sources = sources;
            this.rnd = rnd;
            rangeIdx = new int[sources.size()];
            cursor = new float[sources.size()];
            for (int i = 0; i < sources.size(); i++) {
                float[][] r = sources.get(i).ranges();
                rangeIdx[i] = rnd.nextInt(r.length);
                cursor[i] = r[rangeIdx[i]][0];
            }
        }

        /** Longest piece any clip can give in one go. */
        float maxPiece() {
            float m = 0;
            for (Source s : sources) m = Math.max(m, s.longest());
            return m;
        }

        /** Fills shot.source / shot.sourceOffset for a shot of {@code len} seconds (len must be <= maxPiece()). */
        void fill(Shot shot, float len) {
            int n = sources.size();
            int si = -1;
            for (int tries = 0; tries < 12 && si < 0; tries++) {
                int i = rnd.nextInt(n);
                if (n > 1 && i == last) continue;
                if (sources.get(i).longest() >= len) si = i;
            }
            if (si < 0) {
                for (int i = 0; i < n; i++) {
                    if (n > 1 && i == last && sources.size() > 1) continue;
                    if (si < 0 || sources.get(i).longest() > sources.get(si).longest()) si = i;
                }
            }
            if (si < 0) si = 0;
            Source src = sources.get(si);
            float[][] ranges = src.ranges();
            // stay in the current part if the shot fits, else move to the next part that fits
            float offset = -1;
            for (int k = 0; k < ranges.length && offset < 0; k++) {
                int ri = (rangeIdx[si] + k) % ranges.length;
                float[] r = ranges[ri];
                if (k == 0) {
                    if (cursor[si] + len <= r[1] + 1e-3f) { offset = cursor[si]; rangeIdx[si] = ri; }
                } else if (r[1] - r[0] >= len) {
                    offset = r[0] + rnd.nextFloat() * Math.min(0.5f, r[1] - r[0] - len);
                    rangeIdx[si] = ri;
                }
            }
            if (offset < 0) offset = ranges[0][0];
            float[] r = ranges[rangeIdx[si]];
            float next = offset + len + rnd.nextFloat() * Math.min(1.5f, (r[1] - r[0]) * 0.2f);
            if (next + 0.3f >= r[1]) {
                rangeIdx[si] = (rangeIdx[si] + 1 + (ranges.length > 2 ? rnd.nextInt(ranges.length - 1) : 0)) % ranges.length;
                next = ranges[rangeIdx[si]][0];
            }
            cursor[si] = next;
            last = si;
            shot.source = src.name;
            shot.sourceOffset = Math.max(0, offset);
        }
    }

    /** One shot of the edit. */
    public static final class Shot {
        public String source;
        /** Where the shot sits on the timeline, seconds. */
        public float start;
        public float duration;
        /** Where the shot starts inside its source clip, seconds. */
        public float sourceOffset;
        /** Effect style id (assets/animations/effects/&lt;id&gt;.json), or null. */
        public String effect;
        /** Effect strength (params "intensity"). */
        public float effectStrength = 1f;
        /** Transition into the NEXT shot (assets/animations/transitions/&lt;id&gt;.json), or null for a hard cut. */
        public String transitionOut;
        public float transitionDuration;
        /** Playback speed (0.6 = slow motion, 1.3 = faster). The shot uses duration * speed seconds of its source. */
        public float speed = 1f;
    }

    /** A short hit on the beat, drawn by an effect clip on a layer above the shots (affects the whole picture). */
    public static final class Impact {
        public float time, duration;
        public String effect;
        public float strength = 1f;
    }

    public static final class Plan {
        public final List<Shot> shots = new ArrayList<>();
        public final List<Impact> impacts = new ArrayList<>();
        public float duration;
        /** Colour look to give every shot (filled by the caller when copying a reference): saturation x, brightness +. */
        public float saturation = 1f, brightness = 0f;
    }

    // ================================ finishing touches ================================

    /**
     * Makes the edit feel alive on the music: hits on the strong beats (zoom pulse, shake, flash), slow motion on long
     * calm shots, a little speed on very short ones.
     *
     * @param intensity 0 = none, 1 = normal, 1.5 = hype
     */
    public static void addFinishingTouches(Plan plan, BeatDetector.Result music, float intensity, long seed) {
        if (plan.shots.isEmpty() || intensity <= 0) return;
        Random rnd = new Random(seed ^ 0x5DEECE66DL);

        // ---- cuts land exactly on the hit you hear (within 70 ms) ----
        for (int k = 1; k < plan.shots.size(); k++) {
            Shot a = plan.shots.get(k - 1), s = plan.shots.get(k);
            float hit = nearestOnset(music, s.start, 0.1f, 0.3f);
            if (Float.isNaN(hit)) continue;
            float delta = hit - s.start;
            if (a.duration + delta < 0.15f || s.duration - delta < 0.15f) continue;
            a.duration += delta;
            s.start = hit;
            s.duration -= delta;
        }

        // ---- slow motion on long calm shots (no speed-ups: they look jumpy) ----
        for (Shot s : plan.shots) {
            if (s.speed != 1f) continue;
            if (s.duration >= 1.6f && energyAt(music, s.start) < 0.75f) s.speed = s.duration >= 2.6f ? 0.65f : 0.8f;
        }

        // ---- transitions: centred on the cut, so the switch happens on the beat ----
        for (int k = 0; k + 1 < plan.shots.size(); k++) {
            Shot a = plan.shots.get(k), n = plan.shots.get(k + 1);
            if (a.transitionOut == null) continue;
            float want = "zoom-blur".equals(a.transitionOut) ? 0.4f : "whip-left".equals(a.transitionOut) ? 0.36f : a.transitionDuration;
            a.transitionDuration = Math.min(want, 2f * Math.min(a.duration, n.duration) * 0.45f);
            if (a.transitionDuration < 0.16f) a.transitionOut = null;
        }

        // ---- hits on the strong onsets (the drums), not on a guessed beat grid ----
        float[] on = music.onsets.length > 0 ? music.onsets : music.beats;
        float[] st = music.onsets.length > 0 ? music.onsetStrength : music.beatStrength;
        float thr = intensity >= 1.4f ? 0.55f : intensity >= 1f ? 0.7f : 0.9f;
        float last = -10f;
        int k = 0;
        for (int i = 0; i < on.length; i++) {
            float t = on[i], strength = st[i];
            if (t >= plan.duration - 0.2f) break;
            if (strength < thr || t - last < 0.3f) continue;
            boolean inTransition = false;
            for (Shot s : plan.shots) {
                if (s.transitionOut == null) continue;
                float end = s.start + s.duration;
                if (Math.abs(t - end) < s.transitionDuration * 0.6f) { inTransition = true; break; }
            }
            if (inTransition) continue;
            boolean onCut = false;
            for (Shot s : plan.shots) if (Math.abs(s.start - t) < 0.05f) { onCut = true; break; }
            Impact im = new Impact();
            im.time = t;
            if (strength >= 0.98f) {
                im.effect = onCut ? "flash" : "shake-hit";
                im.duration = onCut ? 0.25f : 0.45f;
                im.strength = 0.8f + 0.4f * intensity;
            } else {
                im.effect = (k++ % 4 == 3) ? "rgb-shift" : "punch-zoom";
                im.duration = 0.35f;
                im.strength = 0.6f + 0.5f * intensity * strength;
            }
            im.duration = Math.min(im.duration, plan.duration - im.time);
            plan.impacts.add(im);
            last = t;
        }
    }

    private static float nearestOnset(BeatDetector.Result r, float t, float window, float minStrength) {
        float best = Float.NaN, bestD = window;
        for (int i = 0; i < r.onsets.length; i++) {
            float d = Math.abs(r.onsets[i] - t);
            if (d <= bestD && r.onsetStrength[i] >= minStrength) { bestD = d; best = r.onsets[i]; }
        }
        return best;
    }

    // ================================ copy a reference edit ================================

    /**
     * Copies the cut rhythm, effects and transitions of a reference edit onto a new song and new clips.
     *
     * <p>If the song is the same one the reference uses (found by lining up their onset envelopes), the cuts land at
     * the same moments of the song. Otherwise every cut keeps its place in the beat grid (e.g. "on the 3rd beat of the
     * bar"), and the pattern repeats if the song is longer than the reference.</p>
     *
     * @param refAudio beats of the reference's own sound, or null when it has none
     */
    public static Plan planFromReference(ReferenceAnalyzer.Pattern ref, BeatDetector.Result refAudio,
                                         BeatDetector.Result music, float songLength, List<Source> sources, long seed) {
        Plan plan = new Plan();
        plan.duration = songLength;
        List<Source> usable = new ArrayList<>();
        for (Source s : sources) if (s.duration >= 0.3f) usable.add(s);
        if (usable.isEmpty() || songLength <= 0 || ref.shots.isEmpty()) return plan;
        Random rnd = new Random(seed);

        // ---- where each reference shot lands on the new song ----
        List<Float> starts = new ArrayList<>();
        List<ReferenceAnalyzer.RefShot> templates = new ArrayList<>();
        float shift = refAudio == null ? Float.NaN : sameSongShift(refAudio, music);
        boolean haveGrid = refAudio != null && refAudio.beats.length >= 4 && music.beats.length >= 4;

        if (!Float.isNaN(shift)) {
            // same song: same moments
            for (ReferenceAnalyzer.RefShot rs : ref.shots) {
                float t = rs.start + shift;
                if (t >= 0 && t < songLength - 0.1f) { starts.add(t); templates.add(rs); }
            }
            if (starts.isEmpty() || starts.get(0) > 0.05f) { starts.add(0, 0f); templates.add(0, ref.shots.get(0)); }
            // song continues after the reference: keep going with the pattern on the beat grid
            float covered = ref.duration + shift;
            if (covered < songLength - 0.5f) appendCycles(ref, refAudio, music, covered, songLength, haveGrid, starts, templates);
        } else {
            appendCycles(ref, refAudio, music, 0f, songLength, haveGrid, starts, templates);
        }

        // ---- drop cuts too close together (keeps the timeline gap-free) ----
        for (int k = starts.size() - 1; k > 0; k--) {
            float next = k + 1 < starts.size() ? starts.get(k + 1) : songLength;
            if (next - starts.get(k) < 0.08f || starts.get(k) - starts.get(k - 1) < 0.08f) {
                starts.remove(k);
                templates.remove(k);
            }
        }

        // ---- shots ----
        SourcePicker picker = new SourcePicker(usable, rnd);
        float maxPiece = picker.maxPiece();
        for (int k = 0; k < starts.size(); k++) {
            float start = starts.get(k);
            float end = k + 1 < starts.size() ? starts.get(k + 1) : songLength;
            ReferenceAnalyzer.RefShot tpl = templates.get(k);
            if (end - start > maxPiece) {
                float mid = start + maxPiece * 0.95f;
                starts.add(k + 1, mid);
                templates.add(k + 1, tpl);
                end = mid;
            }
            float len = end - start;
            Shot shot = new Shot();
            shot.start = start;
            shot.duration = len;
            picker.fill(shot, len);
            shot.effect = tpl.effect;
            shot.effectStrength = tpl.effectStrength;
            if (tpl.transitionOut != null) {
                shot.transitionOut = tpl.transitionOut;
                shot.transitionDuration = Math.min(tpl.transitionDuration, len * 0.5f);
            }
            plan.shots.add(shot);
        }
        // a transition needs room on both sides
        for (int k = 0; k + 1 < plan.shots.size(); k++) {
            Shot a = plan.shots.get(k), b = plan.shots.get(k + 1);
            if (a.transitionOut == null) continue;
            a.transitionDuration = Math.min(a.transitionDuration, Math.min(a.duration, b.duration) * 0.5f);
            if (a.transitionDuration < 0.1f) a.transitionOut = null;
        }
        if (!plan.shots.isEmpty()) plan.shots.get(plan.shots.size() - 1).transitionOut = null;
        return plan;
    }

    /** Repeats the reference pattern from {@code from} to {@code to} seconds of the song, on its beat grid when there is one. */
    private static void appendCycles(ReferenceAnalyzer.Pattern ref, BeatDetector.Result refAudio, BeatDetector.Result music,
                                     float from, float to, boolean haveGrid, List<Float> starts,
                                     List<ReferenceAnalyzer.RefShot> templates) {
        if (haveGrid) {
            float refLenBeats = toBeat(refAudio.beats, ref.duration);
            if (refLenBeats < 1) refLenBeats = 1;
            // start the pattern on a downbeat-ish point: the first beat at/after `from`
            float base = (float) Math.ceil(toBeat(music.beats, from) - 0.05f);
            float refFirst = toBeat(refAudio.beats, 0f);
            for (int cycle = 0; cycle < 1000; cycle++) {
                boolean any = false;
                for (ReferenceAnalyzer.RefShot rs : ref.shots) {
                    float beatPos = base + cycle * refLenBeats + (toBeat(refAudio.beats, rs.start) - refFirst);
                    float t = fromBeat(music.beats, beatPos);
                    if (cycle == 0 && rs == ref.shots.get(0)) t = from;
                    if (t < from - 0.01f) continue;
                    if (t >= to - 0.1f) return;
                    starts.add(t);
                    templates.add(rs);
                    any = true;
                }
                if (!any) return;
            }
        } else {
            // no rhythm to follow: same timing, stretched by nothing
            for (int cycle = 0; cycle < 1000; cycle++) {
                for (ReferenceAnalyzer.RefShot rs : ref.shots) {
                    float t = from + cycle * ref.duration + rs.start;
                    if (t >= to - 0.1f) return;
                    starts.add(t);
                    templates.add(rs);
                }
                if (ref.duration <= 0.1f) return;
            }
        }
    }

    /** Fractional beat number of time t (extrapolated outside the beats with the average period). */
    static float toBeat(float[] beats, float t) {
        int n = beats.length;
        float period = (beats[n - 1] - beats[0]) / (n - 1);
        if (t <= beats[0]) return (t - beats[0]) / period;
        if (t >= beats[n - 1]) return (n - 1) + (t - beats[n - 1]) / period;
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (beats[mid] <= t) lo = mid; else hi = mid; }
        return lo + (t - beats[lo]) / (beats[hi] - beats[lo]);
    }

    /** Time of a fractional beat number (inverse of {@link #toBeat}). */
    static float fromBeat(float[] beats, float b) {
        int n = beats.length;
        float period = (beats[n - 1] - beats[0]) / (n - 1);
        if (b <= 0) return beats[0] + b * period;
        if (b >= n - 1) return beats[n - 1] + (b - (n - 1)) * period;
        int i = (int) Math.floor(b);
        return beats[i] + (b - i) * (beats[i + 1] - beats[i]);
    }

    /**
     * If the reference's sound and the new song are the same recording, returns how many seconds to add to a reference
     * time to get the song time; NaN when they don't match.
     */
    static float sameSongShift(BeatDetector.Result ref, BeatDetector.Result song) {
        float[] a = ref.onsetEnvelope, b = song.onsetEnvelope;
        if (a.length < 50 || b.length < 50 || Math.abs(ref.envelopeRate - song.envelopeRate) > 0.5f) return Float.NaN;
        // compare at ~1/4 resolution to keep it fast
        float[] ra = downsample(a, 4), rb = downsample(b, 4);
        zeroMean(ra); zeroMean(rb);
        int maxLag = rb.length - Math.min(ra.length, rb.length) / 2;
        int minLag = -ra.length / 2;
        double best = 0;
        int bestLag = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double s = 0, ea = 0, eb = 0;
            int i0 = Math.max(0, -lag), i1 = Math.min(ra.length, rb.length - lag);
            if (i1 - i0 < ra.length / 2) continue;
            for (int i = i0; i < i1; i++) {
                float x = ra[i], y = rb[i + lag];
                s += x * y; ea += x * x; eb += y * y;
            }
            if (ea <= 0 || eb <= 0) continue;
            double c = s / Math.sqrt(ea * eb);
            if (c > best) { best = c; bestLag = lag; }
        }
        if (best < 0.45) return Float.NaN;
        return bestLag * 4 / song.envelopeRate;
    }

    private static float[] downsample(float[] x, int k) {
        float[] o = new float[x.length / k];
        for (int i = 0; i < o.length; i++) {
            float m = 0;
            for (int j = 0; j < k; j++) m = Math.max(m, x[i * k + j]);
            o[i] = m;
        }
        return o;
    }

    private static void zeroMean(float[] x) {
        double s = 0;
        for (float v : x) s += v;
        float m = (float) (s / Math.max(1, x.length));
        for (int i = 0; i < x.length; i++) x[i] -= m;
    }

    // ------------------------------------------------------------------------------------------

    private static boolean previousHadEffect(Plan plan) {
        return !plan.shots.isEmpty() && plan.shots.get(plan.shots.size() - 1).effect != null;
    }

    private static int beatsPerShot(Style style, float energy, Random rnd) {
        switch (style) {
            case HYPE:
                if (energy > 0.7f) return rnd.nextFloat() < 0.25f ? 2 : 1;
                return energy > 0.4f ? 2 : 4;
            case BALANCED:
                if (energy > 0.7f) return 2;
                return energy > 0.35f ? (rnd.nextFloat() < 0.5f ? 2 : 4) : 4;
            default:
                return energy > 0.6f ? 4 : 8;
        }
    }

    private static float minShot(Style style) {
        return style == Style.HYPE ? 0.25f : style == Style.BALANCED ? 0.5f : 1.2f;
    }

    private static float hitThreshold(Style style) {
        return style == Style.HYPE ? 0.6f : style == Style.BALANCED ? 0.78f : 0.92f;
    }

    private static int beatIndex(BeatDetector.Result r, float t) {
        float[] b = r.beats;
        if (b.length == 0) return -1;
        int lo = 0, hi = b.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (b[mid] <= t + 0.03f) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    private static float energyAt(BeatDetector.Result r, float t) {
        int i = beatIndex(r, t);
        return i < 0 ? 0.5f : r.beatEnergy[i];
    }

    private static float strengthAt(BeatDetector.Result r, float t) {
        int i = beatIndex(r, t);
        if (i < 0 || Math.abs(r.beats[i] - t) > 0.06f) return 0f;
        return r.beatStrength[i];
    }

    private static String pick(String[] pool, Random rnd) {
        return pool[rnd.nextInt(pool.length)];
    }
}
