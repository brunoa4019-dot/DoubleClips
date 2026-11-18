package com.vanvatcorporation.doubleclips.ai;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Build;

/**
 * Editor IA: samples small frames of the reference edit at a fixed rate and turns each into the features
 * {@link ReferenceAnalyzer} reads (luma histogram, brightness, saturation, motion).
 */
public final class ReferenceFrameSampler {

    public interface Progress {
        void onProgress(int done, int total);
    }

    private static final int W = 64, H = 64;

    private ReferenceFrameSampler() { }

    public static ReferenceAnalyzer.Frames sample(String path, float fps, float maxSeconds, Progress progress) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            float duration = d == null ? 0 : Long.parseLong(d) / 1000f;
            if (duration <= 0) throw new IllegalArgumentException("Não consegui ler a edit de referência.");
            duration = Math.min(duration, maxSeconds);
            int n = Math.max(1, (int) (duration * fps));

            ReferenceAnalyzer.Frames f = new ReferenceAnalyzer.Frames();
            f.fps = fps;
            f.hist = new float[n][ReferenceAnalyzer.HIST_BINS];
            f.brightness = new float[n];
            f.motion = new float[n];
            f.saturation = new float[n];
            int[] px = new int[W * H];
            float[] prevLuma = null;
            float[] luma = new float[W * H];
            for (int i = 0; i < n; i++) {
                long us = (long) (i / fps * 1_000_000L);
                Bitmap bmp;
                if (Build.VERSION.SDK_INT >= 27) bmp = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, W, H);
                else {
                    Bitmap big = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST);
                    bmp = big == null ? null : Bitmap.createScaledBitmap(big, W, H, true);
                    if (big != null && big != bmp) big.recycle();
                }
                if (bmp == null) {
                    // keep the previous frame's features
                    if (i > 0) {
                        f.hist[i] = f.hist[i - 1].clone();
                        f.brightness[i] = f.brightness[i - 1];
                        f.saturation[i] = f.saturation[i - 1];
                    }
                    continue;
                }
                int bw = bmp.getWidth(), bh = bmp.getHeight();
                if (bw * bh > px.length) { px = new int[bw * bh]; luma = new float[bw * bh]; prevLuma = null; }
                bmp.getPixels(px, 0, bw, 0, 0, bw, bh);
                int count = bw * bh;
                float sumL = 0, sumS = 0;
                float[] h = f.hist[i];
                for (int k = 0; k < count; k++) {
                    int c = px[k];
                    float rr = ((c >> 16) & 255) / 255f, gg = ((c >> 8) & 255) / 255f, bb = (c & 255) / 255f;
                    float l = 0.299f * rr + 0.587f * gg + 0.114f * bb;
                    luma[k] = l;
                    sumL += l;
                    float max = Math.max(rr, Math.max(gg, bb)), min = Math.min(rr, Math.min(gg, bb));
                    sumS += max <= 0 ? 0 : (max - min) / max;
                    h[Math.min(ReferenceAnalyzer.HIST_BINS - 1, (int) (l * ReferenceAnalyzer.HIST_BINS))] += 1f / count;
                }
                f.brightness[i] = sumL / count;
                f.saturation[i] = sumS / count;
                if (prevLuma != null && prevLuma.length == count) {
                    float m = 0;
                    for (int k = 0; k < count; k++) m += Math.abs(luma[k] - prevLuma[k]);
                    f.motion[i] = m / count;
                }
                if (prevLuma == null || prevLuma.length != count) prevLuma = new float[count];
                System.arraycopy(luma, 0, prevLuma, 0, count);
                bmp.recycle();
                if (progress != null && (i % 6 == 0 || i == n - 1)) progress.onProgress(i + 1, n);
            }
            return f;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }
}
