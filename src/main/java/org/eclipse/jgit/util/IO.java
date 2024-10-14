/*
 * Copyright (C) 2008-2009, Google Inc.
 * Copyright (C) 2009, Robin Rosenberg
 * <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2008, Shawn O. Pearce
 * <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.util.io.SilentFileInputStream;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Input/Output utilities
 */
public class IO {

    /**
     * Maximum file size that can be read fully into memory.
     * Adjust this value based on your application's requirements.
     */
    public static final int MAX_FILE_SIZE = 50 * 1024 * 1024; // 50 MB

    /**
     * Read an entire local file into memory as a byte array.
     * If the file size exceeds MAX_FILE_SIZE, an IOException is thrown.
     *
     * @param path location of the file to read.
     * @return complete contents of the requested local file.
     * @throws FileNotFoundException the file does not exist.
     * @throws IOException           the file exists, but its contents cannot be read.
     */
    public static final byte[] readFully(File path)
            throws FileNotFoundException, IOException {
        long fileLength = path.length();

        if (fileLength > Integer.MAX_VALUE) {
            throw new IOException("File is too large to read into a byte array");
        }
        if (fileLength > MAX_FILE_SIZE) {
            throw new IOException("File size (" + fileLength + " bytes) exceeds maximum allowed size of " + MAX_FILE_SIZE + " bytes");
        }

        return readFully(path, (int) fileLength);
    }

    /**
     * Read at most limit bytes from the local file into memory as a byte array.
     *
     * @param path  location of the file to read.
     * @param limit maximum number of bytes to read, if the file is larger than
     *              only the first limit number of bytes are returned
     * @return contents of the requested local file up to the specified limit.
     * @throws FileNotFoundException the file does not exist.
     * @throws IOException           the file exists, but its contents cannot be read.
     */
    public static final byte[] readSome(File path, int limit)
            throws FileNotFoundException, IOException {
        try (SilentFileInputStream in = new SilentFileInputStream(path)) {
            return readNBytes(in, limit);
        }
    }

    /**
     * Read an entire local file into memory as a byte array.
     * If the file size exceeds MAX_FILE_SIZE, an IOException is thrown.
     *
     * @param path location of the file to read.
     * @param max  maximum number of bytes to read, if the file is larger than
     *             this limit an IOException is thrown.
     * @return complete contents of the requested local file.
     * @throws FileNotFoundException the file does not exist.
     * @throws IOException           the file exists, but its contents cannot be read.
     */
    public static final byte[] readFully(File path, int max)
            throws FileNotFoundException, IOException {
        if (max < 0) {
            throw new IllegalArgumentException("max must not be negative");
        }
        if (max > MAX_FILE_SIZE) {
            throw new IOException("Requested max length " + max + " exceeds maximum allowed size of " + MAX_FILE_SIZE + " bytes");
        }
        try (SilentFileInputStream in = new SilentFileInputStream(path)) {
            byte[] buf = readNBytes(in, max);
            if (in.read() != -1) {
                throw new IOException(MessageFormat.format(
                        JGitText.get().fileIsTooLarge, path));
            }
            return buf;
        }
    }

    /**
     * Processes a file in chunks to avoid reading the entire file into memory.
     *
     * @param path           the file to process.
     * @param chunkProcessor an instance of ChunkProcessor to handle each chunk of data.
     * @throws IOException if an I/O error occurs.
     */
    public static void processFileInChunks(File path, ChunkProcessor chunkProcessor) throws IOException {
        try (InputStream in = new SilentFileInputStream(path)) {
            byte[] buffer = new byte[8192]; // 8 KB buffer
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                // Process the buffer data
                chunkProcessor.processChunk(buffer, 0, bytesRead);
            }
        }
    }

    /**
     * Interface for processing chunks of data read from a file.
     */
    public interface ChunkProcessor {
        void processChunk(byte[] buffer, int offset, int length) throws IOException;
    }

    /**
     * Read an entire input stream into memory as a ByteBuffer.
     * <p>
     * Note: The stream is read to its end and is not usable after calling this
     * method. The caller is responsible for closing the stream.
     *
     * @param in       input stream to be read.
     * @param sizeHint a hint on the approximate number of bytes contained in the
     *                 stream, used to allocate temporary buffers more efficiently
     * @return complete contents of the input stream. The ByteBuffer always has
     * a writable backing array, with {@code position() == 0} and
     * {@code limit()} equal to the actual length read. Callers may rely
     * on obtaining the underlying array for efficient data access. If
     * {@code sizeHint} was too large, the array may be over-allocated,
     * resulting in {@code limit() < array().length}.
     * @throws IOException there was an error reading from the stream.
     */
    public static ByteBuffer readWholeStream(InputStream in, int sizeHint)
            throws IOException {
        byte[] bytes = readAllBytes(in);
        return ByteBuffer.wrap(bytes);
    }

    /**
     * Read the entire byte array into memory, or throw an exception.
     *
     * @param fd  input stream to read the data from.
     * @param dst buffer that must be fully populated, [off, off+len).
     * @param off position within the buffer to start writing to.
     * @param len number of bytes that must be read.
     * @throws EOFException the stream ended before dst was fully populated.
     * @throws IOException  there was an error reading from the stream.
     */
    public static void readFully(final InputStream fd, final byte[] dst,
                                 int off, int len) throws IOException {
        int totalRead = 0;
        while (totalRead < len) {
            int read = fd.read(dst, off + totalRead, len - totalRead);
            if (read == -1) {
                throw new EOFException(JGitText.get().shortReadOfBlock);
            }
            totalRead += read;
        }
    }

    /**
     * Read from input until the entire byte array filled, or throw an exception
     * if stream ends first.
     *
     * @param fd  input stream to read the data from.
     * @param dst buffer that must be fully populated
     * @throws EOFException the stream ended before dst was fully populated.
     * @throws IOException  there was an error reading from the stream.
     * @since 6.5
     */
    public static void readFully(InputStream fd, byte[] dst)
            throws IOException {
        readFully(fd, dst, 0, dst.length);
    }

    /**
     * Read as much of the array as possible from a channel.
     *
     * @param channel channel to read data from.
     * @param dst     buffer that must be fully populated, [off, off+len).
     * @param off     position within the buffer to start writing to.
     * @param len     number of bytes that should be read.
     * @return number of bytes actually read.
     * @throws IOException there was an error reading from the channel.
     */
    public static int read(ReadableByteChannel channel, byte[] dst, int off,
                           int len) throws IOException {
        if (len == 0)
            return 0;
        int cnt = 0;
        ByteBuffer buffer = ByteBuffer.wrap(dst, off, len);
        while (buffer.hasRemaining()) {
            int r = channel.read(buffer);
            if (r <= 0)
                break;
            cnt += r;
        }
        return cnt != 0 ? cnt : -1;
    }

    /**
     * Read the entire byte array into memory, unless input is shorter
     *
     * @param fd  input stream to read the data from.
     * @param dst buffer that must be fully populated, [off, off+len).
     * @param off position within the buffer to start writing to.
     * @return number of bytes read
     * @throws IOException there was an error reading from the stream.
     */
    public static int readFully(InputStream fd, byte[] dst, int off)
            throws IOException {
        int totalRead = 0;
        int len = dst.length - off;
        while (totalRead < len) {
            int read = fd.read(dst, off + totalRead, len - totalRead);
            if (read == -1) {
                break;
            }
            totalRead += read;
        }
        return totalRead;
    }

    /**
     * Skip an entire region of an input stream.
     * <p>
     * The input stream's position is moved forward by the number of requested
     * bytes, discarding them from the input. This method does not return until
     * the exact number of bytes requested has been skipped.
     *
     * @param fd     the stream to skip bytes from.
     * @param toSkip total number of bytes to be discarded. Must be &gt;= 0.
     * @throws EOFException the stream ended before the requested number of bytes were
     *                      skipped.
     * @throws IOException  there was an error reading from the stream.
     */
    public static void skipFully(InputStream fd, long toSkip)
            throws IOException {
        // Similar to fd.skipNBytes(toSkip) in newer JDKs
        while (toSkip > 0) {
            long skipped = fd.skip(toSkip);
            if (skipped <= 0) {
                // If skip() returns 0, we need to read and discard one byte
                if (fd.read() == -1) {
                    throw new EOFException(JGitText.get().shortSkipOfBlock);
                }
                toSkip--;
            } else {
                toSkip -= skipped;
            }
        }
    }

    /**
     * Divides the given string into lines.
     *
     * @param s the string to read
     * @return the string divided into lines
     * @since 2.0
     */
    public static List<String> readLines(String s) {
        List<String> l = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                l.add(sb.toString());
                sb.setLength(0);
                continue;
            }
            if (c == '\r') {
                if (i + 1 < s.length()) {
                    c = s.charAt(++i);
                    l.add(sb.toString());
                    sb.setLength(0);
                    if (c != '\n') {
                        sb.append(c);
                    }
                    continue;
                }
                // EOF
                l.add(sb.toString());
                break;
            }
            sb.append(c);
        }
        l.add(sb.toString());
        return l;
    }

    /**
     * Read the next line from a reader.
     * <p>
     * Like {@link java.io.BufferedReader#readLine()}, but only treats
     * {@code \n} as end-of-line, and includes the trailing newline.
     *
     * @param in       the reader to read from.
     * @param sizeHint hint for buffer sizing; 0 or negative for default.
     * @return the next line from the input, always ending in {@code \n} unless
     * EOF was reached.
     * @throws IOException there was an error reading from the stream.
     * @since 4.1
     */
    public static String readLine(Reader in, int sizeHint) throws IOException {
        if (in.markSupported()) {
            if (sizeHint <= 0) {
                sizeHint = 1024;
            }
            StringBuilder sb = new StringBuilder(sizeHint);
            char[] buf = new char[sizeHint];
            while (true) {
                in.mark(sizeHint);
                int n = in.read(buf);
                if (n < 0) {
                    in.reset();
                    return sb.toString();
                }
                for (int i = 0; i < n; i++) {
                    if (buf[i] == '\n') {
                        resetAndSkipFully(in, ++i);
                        sb.append(buf, 0, i);
                        return sb.toString();
                    }
                }
                if (n > 0) {
                    sb.append(buf, 0, n);
                }
                resetAndSkipFully(in, n);
            }
        }
        StringBuilder buf = sizeHint > 0 ? new StringBuilder(sizeHint)
                : new StringBuilder();
        int i;
        while ((i = in.read()) != -1) {
            char c = (char) i;
            buf.append(c);
            if (c == '\n') {
                break;
            }
        }
        return buf.toString();
    }

    private static void resetAndSkipFully(Reader fd, long toSkip) throws IOException {
        fd.reset();
        while (toSkip > 0) {
            long skipped = fd.skip(toSkip);
            if (skipped <= 0) {
                if (fd.read() == -1) {
                    throw new EOFException(JGitText.get().shortSkipOfBlock);
                }
                toSkip--;
            } else {
                toSkip -= skipped;
            }
        }
    }

    // Helper methods to replace readNBytes and readAllBytes

    /**
     * Maximum buffer size for reading bytes.
     * Adjust this value based on your application's requirements.
     */
    public static final int MAX_BUFFER_SIZE = 50 * 1024 * 1024; // 50 MB

    /**
     * Reads up to a specified number of bytes from the input stream.
     *
     * @param in  the input stream to read from.
     * @param len the maximum number of bytes to read.
     * @return a byte array containing the bytes read.
     * @throws IOException if an I/O error occurs.
     */
    public static byte[] readNBytes(InputStream in, int len) throws IOException {
        if (len < 0) {
            throw new IllegalArgumentException("len must not be negative");
        }
        if (len > MAX_BUFFER_SIZE) {
            throw new IOException("Requested length " + len + " exceeds maximum allowed size of " + MAX_BUFFER_SIZE + " bytes");
        }

        byte[] result = new byte[len];
        int totalRead = 0;
        while (totalRead < len) {
            int bytesRead = in.read(result, totalRead, len - totalRead);
            if (bytesRead == -1) {
                // Reached EOF before reading expected number of bytes
                if (totalRead == 0) {
                    return new byte[0];
                } else {
                    byte[] truncatedResult = new byte[totalRead];
                    System.arraycopy(result, 0, truncatedResult, 0, totalRead);
                    return truncatedResult;
                }
            }
            totalRead += bytesRead;
        }
        return result;
    }

    /**
     * Reads all remaining bytes from the input stream.
     *
     * @param in the input stream to read from.
     * @return a byte array containing the bytes read.
     * @throws IOException if an I/O error occurs.
     */
    public static byte[] readAllBytes(InputStream in) throws IOException {
        final int DEFAULT_BUFFER_SIZE = 8192;
        List<byte[]> buffers = new ArrayList<>();
        int totalBytesRead = 0;
        int bytesRead;
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        while ((bytesRead = in.read(buffer, 0, DEFAULT_BUFFER_SIZE)) != -1) {
            byte[] bytesReadBuffer = new byte[bytesRead];
            System.arraycopy(buffer, 0, bytesReadBuffer, 0, bytesRead);
            buffers.add(bytesReadBuffer);
            totalBytesRead += bytesRead;

            if (totalBytesRead > MAX_BUFFER_SIZE) {
                throw new IOException("Data exceeds maximum allowed size of " + MAX_BUFFER_SIZE + " bytes");
            }
        }
        byte[] result = new byte[totalBytesRead];
        int offset = 0;
        for (byte[] b : buffers) {
            System.arraycopy(b, 0, result, offset, b.length);
            offset += b.length;
        }
        return result;
    }

    private IO() {
        // Don't create instances of a static only utility.
    }
}
