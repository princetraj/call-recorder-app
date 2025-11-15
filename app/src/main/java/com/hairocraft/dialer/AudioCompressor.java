package com.hairocraft.dialer;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Utility class to compress audio files using Android's MediaCodec
 * Converts any audio format to AAC with optimized bitrate for voice recordings
 */
public class AudioCompressor {
    private static final String TAG = "AudioCompressor";

    // Optimized bitrate for voice recordings (32kbps mono, 64kbps stereo)
    private static final int BITRATE_MONO = 32000;
    private static final int BITRATE_STEREO = 64000;

    // Standard sample rate for AAC
    private static final int SAMPLE_RATE = 44100;

    // Timeout for codec operations
    private static final int TIMEOUT_US = 10000;

    /**
     * Compress an audio file to AAC format with reduced bitrate
     * @param inputFile The input audio file
     * @param outputFile The output compressed file
     * @return true if compression succeeded, false otherwise
     */
    public static boolean compressAudio(File inputFile, File outputFile) {
        if (inputFile == null || !inputFile.exists()) {
            Log.e(TAG, "Input file does not exist");
            return false;
        }

        long startTime = System.currentTimeMillis();
        long originalSize = inputFile.length();

        Log.d(TAG, "Starting compression: " + inputFile.getName() +
              " (size: " + formatBytes(originalSize) + ")");

        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;

        try {
            // Setup extractor to read the input file
            extractor = new MediaExtractor();
            extractor.setDataSource(inputFile.getAbsolutePath());

            // Find the audio track
            int audioTrackIndex = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    inputFormat = format;
                    break;
                }
            }

            if (audioTrackIndex < 0) {
                Log.e(TAG, "No audio track found in file");
                return false;
            }

            extractor.selectTrack(audioTrackIndex);

            // Get input format details
            int channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : SAMPLE_RATE;

            Log.d(TAG, "Input format - Channels: " + channelCount +
                  ", Sample rate: " + sampleRate + "Hz");

            // Create decoder for input file
            String inputMime = inputFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(inputMime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            // Create encoder for AAC output with optimized bitrate
            int bitrate = (channelCount > 1) ? BITRATE_STEREO : BITRATE_MONO;
            MediaFormat outputFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount
            );
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            outputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            Log.d(TAG, "Output format - AAC " + bitrate/1000 + "kbps, " +
                  sampleRate + "Hz, " + channelCount + " channels");

            // Create muxer for output file
            muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // Process the audio
            boolean success = processAudio(extractor, decoder, encoder, muxer);

            if (success) {
                long compressedSize = outputFile.length();
                long timeTaken = System.currentTimeMillis() - startTime;
                double compressionRatio = (1 - (double)compressedSize / originalSize) * 100;

                Log.d(TAG, "Compression completed successfully!");
                Log.d(TAG, "  Original size: " + formatBytes(originalSize));
                Log.d(TAG, "  Compressed size: " + formatBytes(compressedSize));
                Log.d(TAG, "  Reduction: " + String.format("%.1f%%", compressionRatio));
                Log.d(TAG, "  Time taken: " + timeTaken + "ms");
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "Error compressing audio", e);
            // Clean up failed output file
            if (outputFile.exists()) {
                outputFile.delete();
            }
            return false;
        } finally {
            // Release all resources
            if (muxer != null) {
                try {
                    muxer.stop();
                    muxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing muxer", e);
                }
            }
            if (encoder != null) {
                try {
                    encoder.stop();
                    encoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing encoder", e);
                }
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing decoder", e);
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing extractor", e);
                }
            }
        }
    }

    /**
     * Process audio data through decoder and encoder
     */
    private static boolean processAudio(MediaExtractor extractor, MediaCodec decoder,
                                       MediaCodec encoder, MediaMuxer muxer) throws IOException {
        MediaCodec.BufferInfo decoderBufferInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo encoderBufferInfo = new MediaCodec.BufferInfo();

        boolean inputDone = false;
        boolean outputDone = false;
        int outputTrackIndex = -1;
        boolean muxerStarted = false;

        while (!outputDone) {
            // Feed input to decoder
            if (!inputDone) {
                int inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);

                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize,
                            presentationTimeUs, 0);
                        extractor.advance();
                    }
                }
            }

            // Get decoded output and feed to encoder
            int outputBufferIndex = decoder.dequeueOutputBuffer(decoderBufferInfo, TIMEOUT_US);
            if (outputBufferIndex >= 0) {
                ByteBuffer decodedBuffer = decoder.getOutputBuffer(outputBufferIndex);

                // Feed decoded data to encoder
                int encoderInputIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                if (encoderInputIndex >= 0) {
                    ByteBuffer encoderInputBuffer = encoder.getInputBuffer(encoderInputIndex);
                    encoderInputBuffer.clear();
                    encoderInputBuffer.put(decodedBuffer);

                    int flags = decoderBufferInfo.flags;
                    if ((flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoder.queueInputBuffer(encoderInputIndex, 0,
                            decoderBufferInfo.size, decoderBufferInfo.presentationTimeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        encoder.queueInputBuffer(encoderInputIndex, 0,
                            decoderBufferInfo.size, decoderBufferInfo.presentationTimeUs, 0);
                    }
                }

                decoder.releaseOutputBuffer(outputBufferIndex, false);
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "Decoder output format changed");
            }

            // Get encoded output and write to muxer
            int encoderOutputIndex = encoder.dequeueOutputBuffer(encoderBufferInfo, TIMEOUT_US);
            if (encoderOutputIndex >= 0) {
                ByteBuffer encodedBuffer = encoder.getOutputBuffer(encoderOutputIndex);

                if ((encoderBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // Codec config info, not actual data
                    encoderBufferInfo.size = 0;
                }

                if (encoderBufferInfo.size != 0) {
                    if (!muxerStarted) {
                        throw new RuntimeException("Muxer not started");
                    }

                    encodedBuffer.position(encoderBufferInfo.offset);
                    encodedBuffer.limit(encoderBufferInfo.offset + encoderBufferInfo.size);

                    muxer.writeSampleData(outputTrackIndex, encodedBuffer, encoderBufferInfo);
                }

                encoder.releaseOutputBuffer(encoderOutputIndex, false);

                if ((encoderBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            } else if (encoderOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw new RuntimeException("Format changed twice");
                }

                MediaFormat newFormat = encoder.getOutputFormat();
                outputTrackIndex = muxer.addTrack(newFormat);
                muxer.start();
                muxerStarted = true;
                Log.d(TAG, "Muxer started with format: " + newFormat);
            }
        }

        return true;
    }

    /**
     * Format bytes to human readable format
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp-1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
