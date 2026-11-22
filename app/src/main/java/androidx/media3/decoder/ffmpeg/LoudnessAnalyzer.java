package androidx.media3.decoder.ffmpeg;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 用 FFmpeg 扩展解码一段音轨，算平均电平（近似 RMS），给出建议增益。
 * 放在 androidx.media3.decoder.ffmpeg 包内，才能访问包内可见的 FfmpegAudioDecoder。
 * 容器解析优先用 Media3 的 Extractor（能处理 E-AC3 in MKV），失败再退回系统 MediaExtractor。
 */
public final class LoudnessAnalyzer {

    public static final float TARGET_DBFS = -18f;

    private LoudnessAnalyzer() {
    }

    /** 返回建议增益（dB，已裁剪到 ±12dB）；无音轨/失败返回 0。 */
    public static float analyze(Context context, Uri uri, int maxSeconds) {
        if (!FfmpegLibrary.isAvailable()) {
            Log.w("RGLoud", "ffmpeg lib unavailable");
            return 0f;
        }
        float db = analyzeWithExtractor(context, uri, maxSeconds);
        if (db == 0f) {
            db = analyzeWithMediaExtractor(context, uri, maxSeconds);
        }
        return db;
    }

    // ---------------- Media3 Extractor 路线 ----------------

    private static final class Collector implements ExtractorOutput {
        final long maxUs;
        Format format;
        final List<byte[]> samples = new ArrayList<>();
        boolean enough;
        private AudioOutput audio;

        Collector(int maxSeconds) {
            maxUs = maxSeconds * 1_000_000L;
        }

        @Override
        public TrackOutput track(int id, int type) {
            if (type == C.TRACK_TYPE_AUDIO && audio == null) {
                audio = new AudioOutput();
                return audio;
            }
            return new DiscardOutput();
        }

        @Override
        public void endTracks() {
        }

        @Override
        public void seekMap(SeekMap seekMap) {
        }

        final class AudioOutput implements TrackOutput {
            private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            private long lastUs;

            @Override
            public void format(Format f) {
                format = f;
            }

            @Override
            public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart) {
                byte[] tmp = new byte[length];
                try {
                    int read = input.read(tmp, 0, length);
                    if (read > 0) buf.write(tmp, 0, read);
                    return read;
                } catch (Exception e) {
                    return -1;
                }
            }

            @Override
            public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
                byte[] tmp = new byte[length];
                data.readBytes(tmp, 0, length);
                buf.write(tmp, 0, length);
            }

            @Override
            public void sampleMetadata(long timeUs, int flags, int size, int offset, TrackOutput.CryptoData cryptoData) {
                byte[] b = buf.toByteArray();
                buf.reset();
                if (b.length == 0) return;
                samples.add(b);
                lastUs = timeUs;
                if (timeUs > maxUs) enough = true;
            }
        }

        final class DiscardOutput implements TrackOutput {
            @Override
            public void format(Format f) {
            }

            @Override
            public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart) {
                byte[] tmp = new byte[length];
                try {
                    return input.read(tmp, 0, length);
                } catch (Exception e) {
                    return -1;
                }
            }

            @Override
            public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
                data.skipBytes(length);
            }

            @Override
            public void sampleMetadata(long timeUs, int flags, int size, int offset, TrackOutput.CryptoData cryptoData) {
            }
        }
    }

    private static float analyzeWithExtractor(Context context, Uri uri, int maxSeconds) {
        DataSource dataSource = null;
        try {
            DataSource.Factory factory = new DefaultDataSource.Factory(context);
            dataSource = factory.createDataSource();
            long length = dataSource.open(new DataSpec(uri));
            ExtractorInput input = new DefaultExtractorInput(dataSource, 0, length);

            Extractor[] candidates = new DefaultExtractorsFactory().createExtractors();
            Extractor chosen = null;
            for (Extractor e : candidates) {
                try {
                    if (e.sniff(input)) {
                        chosen = e;
                        break;
                    }
                } catch (Throwable ignore) {
                }
                input.resetPeekPosition();
            }
            if (chosen == null) {
                Log.w("RGLoud", "no extractor matched");
                return 0f;
            }

            Collector collector = new Collector(maxSeconds);
            chosen.init(collector);
            PositionHolder position = new PositionHolder();
            while (!collector.enough) {
                int result;
                try {
                    result = chosen.read(input, position);
                } catch (Throwable t) {
                    Log.w("RGLoud", "extractor read failed: " + t);
                    break;
                }
                if (result == Extractor.RESULT_END_OF_INPUT) break;
                if (result == Extractor.RESULT_SEEK) {
                    dataSource.close();
                    dataSource = factory.createDataSource();
                    long newLen = dataSource.open(new DataSpec.Builder()
                            .setUri(uri).setPosition(position.position).build());
                    input = new DefaultExtractorInput(dataSource, position.position, newLen);
                }
            }
            chosen.release();

            Log.i("RGLoud", "extractor samples=" + collector.samples.size()
                    + " format=" + (collector.format == null ? "null"
                    : collector.format.sampleMimeType + "/" + collector.format.sampleRate + "Hz"));
            if (collector.format == null || collector.samples.isEmpty()) return 0f;
            return decodeAndMeasure(collector.format, collector.samples);
        } catch (Throwable t) {
            Log.w("RGLoud", "media3 analyze failed: " + t);
            return 0f;
        } finally {
            if (dataSource != null) {
                try {
                    dataSource.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static float decodeAndMeasure(Format format, List<byte[]> samples) {
        FfmpegAudioDecoder decoder = null;
        try {
            decoder = new FfmpegAudioDecoder(format, 8, 8, 256 * 1024, false);
            double sumSquares = 0;
            long count = 0;
            int index = 0;
            boolean eos = false;

            int channels = Math.max(1, format.channelCount);
            int rate = format.sampleRate > 0 ? format.sampleRate : 48000;
            long windowSamples = (long) rate * channels / 2; // 0.5s
            List<Double> windowRms = new ArrayList<>();
            double windowSum = 0;
            long windowCount = 0;
            for (int guard = 0; guard < 2_000_000; guard++) {
                if (!eos) {
                    DecoderInputBuffer in = decoder.dequeueInputBuffer();
                    if (in != null) {
                        if (index < samples.size()) {
                            byte[] b = samples.get(index);
                            in.clear();
                            in.ensureSpaceForWrite(b.length);
                            in.data.order(ByteOrder.LITTLE_ENDIAN);
                            in.data.put(b);
                            in.data.flip();
                            in.timeUs = index * 10_000L;
                            decoder.queueInputBuffer(in);
                            index++;
                        } else {
                            in.clear();
                            in.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
                            decoder.queueInputBuffer(in);
                            eos = true;
                        }
                    }
                }
                SimpleDecoderOutputBuffer out = decoder.dequeueOutputBuffer();
                if (out != null) {
                    ByteBuffer data = out.data;
                    data.order(ByteOrder.LITTLE_ENDIAN);
                    int n = data.remaining() / 2;
                    for (int i = 0; i < n; i++) {
                        double v = data.getShort() / 32768.0;
                        sumSquares += v * v;
                        windowSum += v * v;
                        count++;
                        windowCount++;
                        if (windowCount >= windowSamples) {
                            double r = Math.sqrt(windowSum / windowCount);
                            if (r > 1e-4) windowRms.add(r);
                            windowSum = 0;
                            windowCount = 0;
                        }
                    }
                    out.release();
                } else if (eos) {
                    break;
                }
            }
            Log.i("RGLoud", "pcm samples=" + count + " sumSq=" + sumSquares);
            if (count == 0) return 0f;
            double level;
            if (windowRms.size() >= 3) {
                windowRms.sort(Double::compareTo);
                int idx = (int) Math.min(windowRms.size() - 1, Math.round(windowRms.size() * 0.9) - 1);
                level = windowRms.get(Math.max(0, idx));
            } else {
                level = Math.sqrt(sumSquares / count);
            }
            if (level <= 1e-6) return 0f;
            float dbfs = (float) (20.0 * Math.log10(level));
            float gain = TARGET_DBFS - dbfs;
            Log.i("RGLoud", "level dbfs=" + dbfs + " windows=" + windowRms.size() + " gain=" + gain);
            return Math.max(-12f, Math.min(9f, gain));
        } catch (Throwable t) {
            Log.w("RGLoud", "decode failed: " + t);
            return 0f;
        } finally {
            if (decoder != null) {
                try {
                    decoder.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---------------- 系统 MediaExtractor 兜底 ----------------

    private static float analyzeWithMediaExtractor(Context context, Uri uri, int maxSeconds) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(context, uri, null);
            int track = -1;
            MediaFormat mf = null;
            String mime = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    track = i;
                    mf = f;
                    mime = m;
                    break;
                }
            }
            if (track < 0 || mf == null) return 0f;
            extractor.selectTrack(track);
            int sampleRate = mf.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? mf.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = mf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? mf.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            Format format = new Format.Builder().setSampleMimeType(mime)
                    .setSampleRate(sampleRate).setChannelCount(channels).build();

            List<byte[]> samples = new ArrayList<>();
            ByteBuffer readBuf = ByteBuffer.allocateDirect(256 * 1024);
            long startUs = -1;
            long maxUs = maxSeconds * 1_000_000L;
            while (true) {
                readBuf.clear();
                int size = extractor.readSampleData(readBuf, 0);
                if (size < 0) break;
                long timeUs = extractor.getSampleTime();
                if (startUs < 0) startUs = timeUs;
                byte[] b = new byte[size];
                readBuf.position(0);
                readBuf.limit(size);
                readBuf.get(b);
                samples.add(b);
                extractor.advance();
                if (timeUs > startUs + maxUs) break;
            }
            Log.i("RGLoud", "mediaextractor samples=" + samples.size());
            if (samples.isEmpty()) return 0f;
            return decodeAndMeasure(format, samples);
        } catch (Throwable t) {
            Log.w("RGLoud", "mediaextractor failed: " + t);
            return 0f;
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
