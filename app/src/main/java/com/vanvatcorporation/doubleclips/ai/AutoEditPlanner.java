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

        public Source(String name, float duration) {
            this.name = name;
            this.duration = duration;
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
    }

    public static final class Plan {
        public final List<Shot> shots = new ArrayList<>();
        public float duration;
    }

    // Effects that read well on anime edits, by role.
    private static final String[] HIT_FX = {"camera-shake", "rgb-shift", "flash", "glitch-pulse", "triggered-shake"};
    private static final String[] DROP_FX = {"white-out", "flash", "lens-flare-surge"};
    private static final String[] LOUD_FX = {"beat-pulse", "heartbeat", "warp-zoom"};
    private static final String[] SOFT_FX = {"slow-push", "vignette", "glow", "drift-blur"};

    private static final String[] HYPE_TRANSITIONS = {"glitchblur", "custom_radial-shockwave", "custom_fake-glass-shatter", "fadewhite", "custom_massive-effect"};
    private static final String[] BALANCED_TRANSITIONS = {"fade", "fadewhite", "glitchblur", "slideleft", "circleopen", "custom_expose"};
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

        // ---- sources: walk through each clip, never the same clip twice in a row ----
        float[] cursor = new float[usable.size()];
        for (int s = 0; s < usable.size(); s++) cursor[s] = rnd.nextFloat() * usable.get(s).duration * 0.3f;
        int lastSource = -1;

        float prevEnergy = energyAt(beats, 0);
        for (int c = 0; c + 1 < cuts.size(); c++) {
            float start = cuts.get(c), end = cuts.get(c + 1);
            float len = end - start;
            int si = pickSource(usable, len, lastSource, rnd);
            Source src = usable.get(si);
            if (len > src.duration) {
                // the clip is shorter than the shot: split the shot in two
                float mid = start + src.duration * 0.95f;
                cuts.add(c + 1, mid);
                end = mid;
                len = end - start;
            }
            float offset = cursor[si];
            if (offset + len > src.duration) offset = rnd.nextFloat() * Math.max(0, src.duration - len);
            cursor[si] = offset + len + rnd.nextFloat() * Math.min(2f, src.duration * 0.15f);
            if (cursor[si] >= src.duration - 0.3f) cursor[si] = rnd.nextFloat() * src.duration * 0.2f;
            lastSource = si;

            Shot shot = new Shot();
            shot.source = src.name;
            shot.start = start;
            shot.duration = len;
            shot.sourceOffset = Math.max(0, offset);

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

    private static int pickSource(List<Source> sources, float len, int last, Random rnd) {
        int n = sources.size();
        // prefer clips long enough for the whole shot
        for (int tries = 0; tries < 8; tries++) {
            int i = rnd.nextInt(n);
            if (n > 1 && i == last) continue;
            if (sources.get(i).duration >= len) return i;
        }
        int best = -1;
        for (int i = 0; i < n; i++) {
            if (n > 1 && i == last) continue;
            if (best < 0 || sources.get(i).duration > sources.get(best).duration) best = i;
        }
        return best < 0 ? 0 : best;
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
