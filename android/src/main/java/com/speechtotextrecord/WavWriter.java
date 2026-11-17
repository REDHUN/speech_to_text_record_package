package com.speechtotextrecord;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Single-worker PCM16 mono WAV file writer.
 * Never buffers an entire recording in memory; streams directly to disk.
 */
final class WavWriter implements Closeable {
    private final RandomAccessFile file;
    private final int sampleRate;
    private long dataBytes;
    private boolean closed;

    WavWriter(File destination, int sampleRate) throws IOException {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("Invalid sample rate: " + sampleRate);
        }
        this.sampleRate = sampleRate;
        this.file = new RandomAccessFile(destination, "rw");
        try {
            this.file.setLength(0);
            this.file.write(new byte[44]); // Reserve header space
        } catch (IOException error) {
            this.file.close();
            throw error;
        }
    }

    void write(byte[] bytes, int count) throws IOException {
        if (closed) {
            throw new IOException("WAV writer already closed");
        }
        if (count < 0 || count > bytes.length || (count & 1) != 0) {
            throw new IllegalArgumentException("Expected complete PCM16 samples, got count: " + count);
        }
        if (dataBytes + count > 0xffffffffL - 36) {
            throw new IOException("WAV size limit (4 GB) exceeded");
        }
        file.write(bytes, 0, count);
        dataBytes += count;
    }

    long durationMs() {
        return dataBytes * 1000L / (sampleRate * 2L);
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            // Truncate possible partially written failed chunk to complete sample boundaries
            file.setLength(44 + dataBytes);

            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            // RIFF header
            h.put(new byte[] {'R', 'I', 'F', 'F'});
            h.putInt((int) (36 + dataBytes));
            h.put(new byte[] {'W', 'A', 'V', 'E'});

            // fmt subchunk
            h.put(new byte[] {'f', 'm', 't', ' '});
            h.putInt(16); // SubChunk1Size (16 for PCM)
            h.putShort((short) 1); // AudioFormat (1 for PCM)
            h.putShort((short) 1); // NumChannels (1 = Mono)
            h.putInt(sampleRate); // SampleRate
            h.putInt(sampleRate * 2); // ByteRate (SampleRate * NumChannels * BitsPerSample/8)
            h.putShort((short) 2); // BlockAlign (NumChannels * BitsPerSample/8)
            h.putShort((short) 16); // BitsPerSample

            // data subchunk
            h.put(new byte[] {'d', 'a', 't', 'a'});
            h.putInt((int) dataBytes);

            file.seek(0);
            file.write(h.array());
        } finally {
            file.close();
        }
    }
}
