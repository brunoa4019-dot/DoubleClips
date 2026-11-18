package com.vanvatcorporation.doubleclips.ai;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Editor IA: decodes the first audio track of a file (song or video) into mono float samples at about
 * {@link #TARGET_RATE} Hz, which is all {@link BeatDetector} needs.
 */
public final class AudioPcmDecoder {

    public static final int TARGET_RATE = 22050;

    public static final class Pcm {
        public float[] samples;
        public int sampleRate;
    }

    private AudioPcmDecoder() { }

    public static Pcm decode(String path, float maxSeconds) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(path);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; format = f; break; }
            }
            if (track < 0) throw new IllegalArgumentException("Sem trilha de áudio");
            extractor.selectTrack(track);

            int srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int step = Math.max(1, Math.round(srcRate / (float) TARGET_RATE));
            int outRate = srcRate / step;
            int capacity = (int) Math.min((long) outRate * (long) maxSeconds + outRate, 60L * 15 * outRate);
            float[] out = new float[capacity];
            int written = 0;

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            boolean floatPcm = false;
            float acc = 0;
            int accCount = 0;
            while (!outputDone && written < capacity) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int size = buf == null ? -1 : extractor.readSampleData(buf, 0);
                        if (size < 0 || extractor.getSampleTime() > (long) (maxSeconds * 1_000_000L)) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        floatPcm = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                } else if (outIndex >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(outIndex);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ByteBuffer le = ob.slice().order(ByteOrder.LITTLE_ENDIAN);
                        int bytesPerSample = floatPcm ? 4 : 2;
                        int frames = info.size / (bytesPerSample * Math.max(1, channels));
                        for (int f = 0; f < frames && written < capacity; f++) {
                            float sum = 0;
                            for (int c = 0; c < channels; c++) {
                                int pos = (f * channels + c) * bytesPerSample;
                                sum += floatPcm ? le.getFloat(pos) : le.getShort(pos) / 32768f;
                            }
                            acc += sum / channels;
                            if (++accCount == step) {
                                out[written++] = acc / step;
                                acc = 0;
                                accCount = 0;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
            Pcm pcm = new Pcm();
            pcm.samples = java.util.Arrays.copyOf(out, written);
            pcm.sampleRate = outRate;
            return pcm;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            extractor.release();
        }
    }
}
