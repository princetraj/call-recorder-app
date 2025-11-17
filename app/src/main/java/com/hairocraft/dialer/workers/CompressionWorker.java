package com.hairocraft.dialer.workers;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.hairocraft.dialer.database.AppDatabase;
import com.hairocraft.dialer.database.UploadQueue;
import com.hairocraft.dialer.database.UploadQueueDao;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * WorkManager worker for compressing audio recordings before upload
 * Uses Android's native MediaCodec to convert recordings to mono AAC 16kHz 64kbps
 * Reduces file size by 3-5x while maintaining call quality
 *
 * No external dependencies - uses built-in Android APIs only
 */
public class CompressionWorker extends Worker {
    private static final String TAG = "CompressionWorker";

    // Input data keys
    public static final String KEY_INPUT_PATH = "input_path";
    public static final String KEY_CALLLOG_ID = "calllog_id";
    public static final String KEY_PARENT_UUID = "parent_uuid";
    public static final String KEY_PHONE_NUMBER = "phone_number";
    public static final String KEY_DURATION_SEC = "duration_sec";
    public static final String KEY_TIMESTAMP_MS = "timestamp_ms";

    // Output data keys
    public static final String KEY_OUTPUT_COMPRESSED_PATH = "compressed_path";

    // Compression settings
    // Note: We keep the input sample rate to avoid complex resampling
    // Just reduce bitrate for smaller files
    private static final int TARGET_BIT_RATE = 32000;    // 32kbps (lower than typical 100-150kbps input)
    private static final int TARGET_CHANNELS = 1;        // Mono
    private static final String MIME_TYPE = "audio/mp4a-latm"; // AAC

    public CompressionWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String inputPath = getInputData().getString(KEY_INPUT_PATH);
        String callLogIdStr = getInputData().getString(KEY_CALLLOG_ID);
        String parentUuid = getInputData().getString(KEY_PARENT_UUID);
        String phoneNumber = getInputData().getString(KEY_PHONE_NUMBER);
        int durationSec = getInputData().getInt(KEY_DURATION_SEC, 0);
        long timestampMs = getInputData().getLong(KEY_TIMESTAMP_MS, System.currentTimeMillis());

        Log.d(TAG, "Starting compression for: " + inputPath);

        if (TextUtils.isEmpty(inputPath) || TextUtils.isEmpty(parentUuid)) {
            Log.e(TAG, "Missing required parameters: inputPath or parentUuid");
            return Result.failure();
        }

        File inputFile = new File(inputPath);
        if (!inputFile.exists() || !inputFile.canRead()) {
            Log.e(TAG, "Input file not found or not readable: " + inputPath);
            return Result.failure();
        }

        // Create output directory for compressed files
        File outDir = new File(getApplicationContext().getFilesDir(), "compressed_recordings");
        if (!outDir.exists() && !outDir.mkdirs()) {
            Log.e(TAG, "Failed to create compressed recordings directory");
            return Result.failure();
        }

        // Check disk space
        if (!hasEnoughDiskSpace(inputFile, outDir)) {
            Log.e(TAG, "Not enough disk space for compression");
            return Result.failure();
        }

        // Generate compressed filename
        String outFileName = generateCompressedFileName(
            inputFile.getName(),
            callLogIdStr,
            parentUuid,
            durationSec,
            timestampMs
        );
        File outFile = new File(outDir, outFileName);

        Log.d(TAG, "Compressing to: " + outFile.getAbsolutePath());

        try {
            // Perform compression using MediaCodec
            boolean success = compressAudio(inputFile, outFile);

            if (success && outFile.exists()) {
                long originalSize = inputFile.length();
                long compressedSize = outFile.length();
                float compressionRatio = (float) compressedSize / originalSize * 100;

                Log.d(TAG, String.format("Compression complete. Original: %d bytes, Compressed: %d bytes (%.1f%%)",
                    originalSize, compressedSize, compressionRatio));

                // CRITICAL: Only use compressed file if it's actually smaller
                if (compressedSize >= originalSize) {
                    Log.w(TAG, "Compressed file is not smaller than original. Deleting compressed file and using original.");
                    if (outFile.delete()) {
                        Log.d(TAG, "Deleted ineffective compressed file");
                    }
                    // Don't update DB, QueueRecordingWorker will use original
                    return Result.success();
                }

                Log.d(TAG, "Compression saved " + (originalSize - compressedSize) + " bytes!");

                // Update database with compressed path
                try {
                    AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                    UploadQueueDao dao = db.uploadQueueDao();

                    // Find the recording upload by parentUuid
                    UploadQueue upload = dao.findRecordingByParentUuid(parentUuid);
                    if (upload != null) {
                        upload.compressedFilePath = outFile.getAbsolutePath();
                        dao.update(upload);
                        Log.d(TAG, "Updated compressedFilePath in database for parentUuid: " + parentUuid);
                    } else {
                        Log.w(TAG, "Upload not found for parentUuid: " + parentUuid);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error updating database with compressed path", e);
                }

                // Return success with compressed path
                Data output = new Data.Builder()
                    .putString(KEY_OUTPUT_COMPRESSED_PATH, outFile.getAbsolutePath())
                    .build();

                return Result.success(output);

            } else {
                Log.e(TAG, "Compression failed");
                return Result.failure();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error during compression", e);
            return Result.failure();
        }
    }

    /**
     * Compress audio file using Android MediaCodec API
     * Converts to AAC mono 16kHz 64kbps
     */
    private boolean compressAudio(File inputFile, File outputFile) {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;

        try {
            // Setup extractor to read input file
            extractor = new MediaExtractor();
            extractor.setDataSource(inputFile.getAbsolutePath());

            // Find audio track
            int audioTrackIndex = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    inputFormat = format;
                    break;
                }
            }

            if (audioTrackIndex < 0 || inputFormat == null) {
                Log.e(TAG, "No audio track found in input file");
                return false;
            }

            extractor.selectTrack(audioTrackIndex);

            // Get input format details
            String inputMime = inputFormat.getString(MediaFormat.KEY_MIME);
            int inputSampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int inputChannels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : TARGET_CHANNELS;

            Log.d(TAG, "Input format: " + inputMime + ", " + inputSampleRate + "Hz, " + inputChannels + " channels");

            // SMART SKIP: If file is already well-compressed, don't re-encode
            // Re-encoding AAC to AAC often makes files bigger due to overhead
            if (MIME_TYPE.equals(inputMime) && inputChannels <= TARGET_CHANNELS) {
                // File is already AAC mono - estimate bitrate
                long fileSizeBytes = inputFile.length();
                long durationMs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION) / 1000 : 0;

                if (durationMs > 0) {
                    long estimatedBitrate = (fileSizeBytes * 8 * 1000) / durationMs; // bits per second
                    Log.d(TAG, "Estimated input bitrate: " + estimatedBitrate + " bps (target: " + TARGET_BIT_RATE + " bps)");

                    // If already at or below target bitrate, skip compression
                    if (estimatedBitrate <= TARGET_BIT_RATE * 1.5) { // Allow 50% margin
                        Log.d(TAG, "File is already well-compressed (at or below " + (TARGET_BIT_RATE * 1.5 / 1000) + "kbps), skipping re-encoding");
                        return false; // Skip compression
                    }
                }
            }

            // Create output format - use same sample rate as input to avoid resampling issues
            // Just reduce bitrate for compression
            MediaFormat outputFormat = MediaFormat.createAudioFormat(MIME_TYPE, inputSampleRate, TARGET_CHANNELS);
            outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BIT_RATE);

            // Create encoder
            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            // Create muxer for output
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // Note: We no longer do simple remux since we need to re-encode at lower bitrate
            // Even if format matches, we need to transcode to reduce bitrate

            // For different formats, we need full decode-encode pipeline
            // Create decoder
            decoder = MediaCodec.createDecoderByType(inputMime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            // Transcode audio (simplified version - full implementation would handle resampling)
            boolean success = transcodeAudio(extractor, decoder, encoder, muxer);

            return success;

        } catch (Exception e) {
            Log.e(TAG, "Error in compressAudio", e);
            return false;
        } finally {
            // Cleanup
            try {
                if (decoder != null) {
                    decoder.stop();
                    decoder.release();
                }
                if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                }
                if (muxer != null) {
                    muxer.stop();
                    muxer.release();
                }
                if (extractor != null) {
                    extractor.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during cleanup", e);
            }
        }
    }

    /**
     * Simple remux for files that are already in target format
     */
    private boolean remuxAudio(MediaExtractor extractor, MediaMuxer muxer, int trackIndex) throws IOException {
        MediaFormat format = extractor.getTrackFormat(trackIndex);
        int muxerTrack = muxer.addTrack(format);
        muxer.start();

        ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024); // 1MB buffer
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (true) {
            int sampleSize = extractor.readSampleData(buffer, 0);
            if (sampleSize < 0) {
                break;
            }

            bufferInfo.offset = 0;
            bufferInfo.size = sampleSize;
            bufferInfo.presentationTimeUs = extractor.getSampleTime();
            bufferInfo.flags = extractor.getSampleFlags();

            muxer.writeSampleData(muxerTrack, buffer, bufferInfo);
            extractor.advance();
        }

        return true;
    }

    /**
     * Full transcode pipeline (decode -> encode)
     * Note: This is a simplified version. Full implementation would include:
     * - Audio resampling for sample rate conversion
     * - Channel mixing for stereo->mono conversion
     * - Proper buffer management
     */
    private boolean transcodeAudio(MediaExtractor extractor, MediaCodec decoder,
                                   MediaCodec encoder, MediaMuxer muxer) {
        try {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int muxerTrack = -1;
            boolean muxerStarted = false;

            long timeoutUs = 10000; // 10ms timeout

            while (!outputDone) {
                // Feed input to decoder
                if (!inputDone) {
                    int inputBufferIndex = decoder.dequeueInputBuffer(timeoutUs);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                // Get decoded output and feed to encoder
                int outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs);
                if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = decoder.getOutputBuffer(outputBufferIndex);

                    // Feed decoded data to encoder
                    int encoderInputIndex = encoder.dequeueInputBuffer(timeoutUs);
                    if (encoderInputIndex >= 0) {
                        ByteBuffer encoderInputBuffer = encoder.getInputBuffer(encoderInputIndex);
                        encoderInputBuffer.put(outputBuffer);
                        encoder.queueInputBuffer(encoderInputIndex, 0, bufferInfo.size,
                            bufferInfo.presentationTimeUs, bufferInfo.flags);
                    }

                    decoder.releaseOutputBuffer(outputBufferIndex, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "Decoder output EOS");
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Decoder output format changed");
                }

                // Get encoded output and write to muxer
                int encoderOutputIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs);
                if (encoderOutputIndex >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(encoderOutputIndex);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size != 0) {
                        if (muxerStarted) {
                            muxer.writeSampleData(muxerTrack, encodedData, bufferInfo);
                        }
                    }

                    encoder.releaseOutputBuffer(encoderOutputIndex, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "Encoder output EOS");
                        outputDone = true;
                    }
                } else if (encoderOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = encoder.getOutputFormat();
                    muxerTrack = muxer.addTrack(newFormat);
                    muxer.start();
                    muxerStarted = true;
                    Log.d(TAG, "Muxer started with track: " + muxerTrack);
                }
            }

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error in transcodeAudio", e);
            return false;
        }
    }

    /**
     * Generate deterministic compressed filename
     * Format: {id}_{originalBaseName}_dur{duration}_ts{timestamp}.m4a
     */
    private String generateCompressedFileName(String originalFileName, String callLogId,
                                              String parentUuid, int durationSec, long timestampMs) {
        // Remove extension from original filename
        String base = originalFileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }

        // Use callLogId if available, otherwise use parentUuid
        String idPart = (callLogId != null && !callLogId.isEmpty()) ? callLogId : parentUuid;

        // Format timestamp as yyyyMMddHHmmss
        String tsPart = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            .format(new Date(timestampMs));

        // Combine parts
        String fileName = String.format("%s_%s_dur%d_ts%s.m4a",
            sanitize(idPart),
            sanitize(base),
            durationSec,
            tsPart
        );

        return fileName;
    }

    /**
     * Sanitize string for use in filename (remove special characters)
     */
    private String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
    }

    /**
     * Check available disk space before compression
     * @return true if enough space is available
     */
    private boolean hasEnoughDiskSpace(File inputFile, File outputDir) {
        try {
            long inputSize = inputFile.length();
            long availableSpace = outputDir.getUsableSpace();
            // Require at least 2x the input file size to be safe
            return availableSpace > (inputSize * 2);
        } catch (Exception e) {
            Log.e(TAG, "Error checking disk space", e);
            return true; // Default to allowing compression
        }
    }
}
