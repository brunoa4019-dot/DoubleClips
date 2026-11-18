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
        /** Average brightness of each good range (same order), 0..1; optional. */
        public float[] rangeLight;

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
        /** Per source, per range: how far into the range has been used already (seconds from the range start). */
        private final float[][] used;
        private int last = -1;

        SourcePicker(List<Source> sources, Random rnd) {
            this.sources = sources;
            this.rnd = rnd;
            used = new float[sources.size()][];
            for (int i = 0; i < sources.size(); i++) used[i] = new float[sources.get(i).ranges().length];
        }

        /** Longest piece any clip can give in one go. */
        float maxPiece() {
            float m = 0;
            for (Source s : sources) m = Math.max(m, s.longest());
            return m;
        }

        void fill(Shot shot, float len) { fill(shot, len, Float.NaN); }

        /**
         * Fills shot.source / shot.sourceOffset: footage not used yet first (no repeats while there is fresh footage), a
         * scene whose brightness is close to {@code wantLight} when given, never the same clip twice in a row.
         */
        void fill(Shot shot, float len, float wantLight) {
            int bestS = -1, bestR = -1;
            double bestScore = Double.MAX_VALUE;
            for (int si = 0; si < sources.size(); si++) {
                Source src = sources.get(si);
                float[][] r = src.ranges();
                for (int ri = 0; ri < r.length; ri++) {
                    float free = (r[ri][1] - r[ri][0]) - used[si][ri];
                    if (free < len) continue;
                    double score = rnd.nextDouble() * 0.15;
                    if (!Float.isNaN(wantLight) && src.rangeLight != null && ri < src.rangeLight.length)
                        score += Math.abs(src.rangeLight[ri] - wantLight) * 2.0;
                    if (sources.size() > 1 && si == last) score += 0.5;
                    score += used[si][ri] > 0 ? 0.08 : 0; // fresh scenes first
                    if (score < bestScore) { bestScore = score; bestS = si; bestR = ri; }
                }
            }
            if (bestS < 0) {
                // everything used: start over (repeats are unavoidable now), still matching the light
                for (float[] u : used) java.util.Arrays.fill(u, 0f);
                for (int si = 0; si < sources.size(); si++) {
                    float[][] r = sources.get(si).ranges();
                    for (int ri = 0; ri < r.length; ri++) {
                        if (r[ri][1] - r[ri][0] < len) continue;
                        double score = rnd.nextDouble();
                        if (score < bestScore) { bestScore = score; bestS = si; bestR = ri; }
                    }
                }
            }
            if (bestS < 0) { bestS = 0; bestR = 0; }
            Source src = sources.get(bestS);
            float[] r = src.ranges()[bestR];
            float offset = r[0] + used[bestS][bestR];
            if (offset + len > r[1]) offset = Math.max(r[0], r[1] - len);
            used[bestS][bestR] = Math.min(r[1] - r[0], (offset - r[0]) + len + 0.15f);
            last = bestS;
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
        /** Camera work copied from the reference (see ReferenceAnalyzer.RefShot), sampled at camFps over the shot; or null. */
        public float[] camZoom, camX, camY, light;
        public float camFps;
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
    /** Reference mode: the reference already says what happens; only put the cuts exactly on the hits. */
    public static void snapToHits(Plan plan, BeatDetector.Result music) {
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
        for (int k = 0; k + 1 < plan.shots.size(); k++) {
            Shot a = plan.shots.get(k), n = plan.shots.get(k + 1);
            if (a.transitionOut == null) continue;
            a.transitionDuration = Math.min(a.transitionDuration, 2f * Math.min(a.duration, n.duration) * 0.45f);
            if (a.transitionDuration < 0.16f) a.transitionOut = null;
        }
    }

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

    // Effects that read well on anime edits, by role.
    private static final String[] HIT_FX = {"punch-zoom", "shake-hit", "rgb-shift", "flash"};
    private static final String[] DROP_FX = {"white-out", "flash", "lens-flare-surge"};
    private static final String[] LOUD_FX = {"beat-pulse", "heartbeat", "warp-zoom"};
    private static final String[] SOFT_FX = {"slow-push", "vignette", "glow", "drift-blur"};

    private static final String[] HYPE_TRANSITIONS = {"zoom-blur", "whip-left", "zoom-blur", "custom_radial-shockwave", "fadewhite"};
    private static final String[] BALANCED_TRANSITIONS = {"zoom-blur", "whip-left", "fade", "fadewhite"};
    private static final String[] CHILL_TRANSITIONS = {"fade", "dissolve", "fadeblack", "hblur", "radial"};

    private AutoEditPlanner() { }

    /**
     * @param beats       from {@link BeatDetector}
     * @param songLength  length of the edit in seconds (usually the song's)
     * @param seed        same seed + same input = same edit; a new seed gives a new version
     */
    public static Plan plan(BeatDetector.Result beats, float songLength, List<Source> sources, Style style, long seed) {
        Plan plan = new Plan();
        plan.duration = songLength;
        List<Source> usable = new ArrayList<>();
        for (Source s : sources) if (s.duration >= 0.3f) usable.add(s);
        if (usable.isEmpty() || songLength <= 0) return plan;
        Random rnd = new Random(seed);

        // ---- cut points ----
        List<Float> cuts = new ArrayList<>();
        cuts.add(0f);
        float[] b = beats.beats;
        if (b.length < 4) {
            // no usable rhythm: even cuts
            float step = style == Style.HYPE ? 0.8f : style == Style.BALANCED ? 1.6f : 3.2f;
            for (float t = step; t < songLength - 0.2f; t += step) cuts.add(t);
        } else {
            int i = 0;
            while (i < b.length) {
                float e = beats.beatEnergy[i];
                int every = beatsPerShot(style, e, rnd);
                // hard hit inside the next shot? cut right on it
                int next = Math.min(b.length, i + every);
                for (int k = i + 1; k < next; k++) {
                    if (beats.beatStrength[k] > 0.85f && style != Style.CHILL) { next = k; break; }
                }
                if (next >= b.length) break;
                float t = b[next];
                if (t > songLength - 0.2f) break;
                if (t - cuts.get(cuts.size() - 1) >= minShot(style)) cuts.add(t);
                i = next;
            }
        }
        cuts.add(songLength);

        // ---- sources ----
        SourcePicker picker = new SourcePicker(usable, rnd);
        float maxPiece = picker.maxPiece();

        float prevEnergy = energyAt(beats, 0);
        for (int c = 0; c + 1 < cuts.size(); c++) {
            float start = cuts.get(c), end = cuts.get(c + 1);
            if (end - start > maxPiece) {
                // no clip part is that long: split the shot
                float mid = start + maxPiece * 0.95f;
                cuts.add(c + 1, mid);
                end = mid;
            }
            float len = end - start;
            Shot shot = new Shot();
            shot.start = start;
            shot.duration = len;
            picker.fill(shot, len);

            // ---- effects ----
            float strength = strengthAt(beats, start);
            float energy = energyAt(beats, start);
            boolean drop = energy - prevEnergy > 0.35f;
            prevEnergy = energy;
            if (drop && style != Style.CHILL) {
                shot.effect = pick(DROP_FX, rnd);
                shot.effectStrength = 1.2f;
            } else if (strength > hitThreshold(style) && !previousHadEffect(plan)) {
                shot.effect = style == Style.CHILL ? pick(SOFT_FX, rnd) : pick(HIT_FX, rnd);
                shot.effectStrength = 0.8f + strength * 0.6f;
            } else if (energy > 0.75f && style == Style.HYPE && rnd.nextFloat() < 0.35f) {
                shot.effect = pick(LOUD_FX, rnd);
            } else if (style == Style.CHILL && len > 3f && rnd.nextFloat() < 0.4f) {
                shot.effect = pick(SOFT_FX, rnd);
            }
            plan.shots.add(shot);
        }

        // ---- transitions: on big changes of the song, and now and then ----
        for (int k = 0; k + 1 < plan.shots.size(); k++) {
            Shot a = plan.shots.get(k), n = plan.shots.get(k + 1);
            float change = Math.abs(energyAt(beats, n.start) - energyAt(beats, a.start));
            float chance = style == Style.HYPE ? 0.08f : style == Style.BALANCED ? 0.18f : 0.45f;
            boolean section = change > 0.3f;
            if (!section && rnd.nextFloat() > chance) continue;
            float d = style == Style.HYPE ? 0.25f : style == Style.BALANCED ? 0.4f : 0.7f;
            d = Math.min(d, Math.min(a.duration, n.duration) * 0.5f);
            if (d < 0.12f) continue;
            String[] pool = style == Style.HYPE ? HYPE_TRANSITIONS : style == Style.BALANCED ? BALANCED_TRANSITIONS : CHILL_TRANSITIONS;
            a.transitionOut = pick(pool, rnd);
            a.transitionDuration = d;
        }
        return plan;
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

        // ---- lock the cuts to the impacts of the song ----
        lockCutsToHits(starts, templates, music, songLength);

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
            picker.fill(shot, len * 1f, tpl.camZoom != null ? tpl.brightness : Float.NaN);
            if (tpl.camZoom != null) {
                shot.camZoom = tpl.camZoom; shot.camX = tpl.camX; shot.camY = tpl.camY; shot.light = tpl.light;
                shot.camFps = tpl.camFps * (tpl.end - tpl.start > 0.01f ? (tpl.end - tpl.start) / len : 1f) / 1f;
            }
            // with the light curve copied, the reference's flashes are already in it: no extra flash effect on top
            shot.effect = tpl.camZoom != null ? null : tpl.effect;
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

    /**
     * Every cut moves onto the strongest impact near it (up to 0.3 s away), and every big impact that has no cut near it
     * gets one: the scene changes exactly on the hits you hear.
     */
    static void lockCutsToHits(List<Float> starts, List<ReferenceAnalyzer.RefShot> templates, BeatDetector.Result music, float songLength) {
        if (music == null || music.onsets.length == 0) return;
        // 1) move each cut onto the best hit around it
        for (int k = 1; k < starts.size(); k++) {
            float t = starts.get(k);
            float best = Float.NaN;
            double bestScore = -1;
            for (int i = 0; i < music.onsets.length; i++) {
                float d = Math.abs(music.onsets[i] - t);
                // only real impacts count; a very close weak hit is still better than none
                boolean impact = music.onsetStrength[i] >= 0.6f;
                if (d > (impact ? 0.3f : 0.06f)) continue;
                if (starts.contains(music.onsets[i])) continue;
                double score = music.onsetStrength[i] - 1.5 * d + (impact ? 1.0 : 0.0); // impacts always win
                if (score > bestScore) { bestScore = score; best = music.onsets[i]; }
            }
            if (Float.isNaN(best) || music.onsetStrength.length == 0) continue;
            float prev = starts.get(k - 1), next = k + 1 < starts.size() ? starts.get(k + 1) : songLength;
            if (best - prev >= 0.15f && next - best >= 0.15f) starts.set(k, best);
        }
        // 2) big impacts without a cut get one
        for (int i = 0; i < music.onsets.length; i++) {
            if (music.onsetStrength[i] < 0.95f) continue;
            float t = music.onsets[i];
            if (t < 0.3f || t > songLength - 0.3f) continue;
            int k = 0;
            while (k + 1 < starts.size() && starts.get(k + 1) <= t) k++;
            float a = starts.get(k), b = k + 1 < starts.size() ? starts.get(k + 1) : songLength;
            if (t - a < 0.3f || b - t < 0.3f) continue;
            starts.add(k + 1, t);
            templates.add(k + 1, sliceTemplate(templates.get(k), t - a));
        }
    }

    /** The rest of a reference shot from {@code offset} seconds on (its camera continues from there). */
    static ReferenceAnalyzer.RefShot sliceTemplate(ReferenceAnalyzer.RefShot tpl, float offset) {
        ReferenceAnalyzer.RefShot r = new ReferenceAnalyzer.RefShot();
        r.start = tpl.start + offset;
        r.end = Math.max(r.start + 0.05f, tpl.end);
        r.effect = null;
        r.transitionOut = tpl.transitionOut;
        r.transitionDuration = tpl.transitionDuration;
        r.motion = tpl.motion;
        r.brightness = tpl.brightness;
        tpl.transitionOut = null;
        if (tpl.camZoom != null) {
            int from = Math.min(tpl.camZoom.length - 1, Math.max(0, Math.round(offset * tpl.camFps)));
            int n = tpl.camZoom.length - from;
            r.camFps = tpl.camFps;
            r.camZoom = new float[n]; r.camX = new float[n]; r.camY = new float[n]; r.light = new float[n];
            float z0 = tpl.camZoom[from], x0 = tpl.camX[from], y0 = tpl.camY[from];
            for (int i = 0; i < n; i++) {
                r.camZoom[i] = tpl.camZoom[from + i] / z0;
                r.camX[i] = tpl.camX[from + i] - x0;
                r.camY[i] = tpl.camY[from + i] - y0;
                r.light[i] = tpl.light[from + i];
            }
        }
        return r;
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
