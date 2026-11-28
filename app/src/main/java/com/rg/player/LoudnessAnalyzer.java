package com.rg.player;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/** 粗略分析视频音轨响度，返回建议增益（dB），结果按视频缓存。 */
final class LoudnessAnalyzer {

    private LoudnessAnalyzer() {
    }

    static float analyze(Context c, VideoItem item) {
        if (Settings.hasLoudGain(c, item.uri)) return Settings.getLoudGain(c, item.uri);
        MediaExtractor ex = null;
        MediaCodec dec = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(c, item.uri, null);
            int track = -1;
            String mime = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    track = i;
                    mime = m;
                    break;
                }
            }
            if (track < 0 || mime == null) return 0f;
            ex.selectTrack(track);
            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(ex.getTrackFormat(track), null, null, 0);
            dec.start();

            int rate = 48000, channels = 2;
            long sum = 0;
            long count = 0;
            long maxCount = 48000L * 2 * 12;
            long start = System.currentTimeMillis();
            boolean inputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (count < maxCount && System.currentTimeMillis() - start < 8000) {
                if (!inputDone) {
                    int in = dec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(in);
                        int size = ib == null ? -1 : ex.readSampleData(ib, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(in, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(in, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int out = dec.dequeueOutputBuffer(info, 10000);
                if (out >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(out);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ShortBuffer sb = ob.asShortBuffer();
                        while (sb.hasRemaining() && count < maxCount) {
                            int s = sb.get();
                            sum += (long) s * s;
                            count++;
                        }
                    }
                    dec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = dec.getOutputFormat();
                    try {
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        maxCount = (long) rate * Math.max(1, channels) * 12;
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (count < 1000) return 0f;
            double rms = Math.sqrt(sum / (double) count) / 32768.0;
            double db = 20 * Math.log10(Math.max(rms, 1e-6));
            double gain = Math.max(-6.0, Math.min(9.0, -18.0 - db));
            Settings.putLoudGain(c, item.uri, (float) gain);
            return (float) gain;
        } catch (Throwable t) {
            return 0f;
        } finally {
            try {
                if (dec != null) {
                    dec.stop();
                    dec.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (ex != null) ex.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
