/*
 * Copyright (C) 2022, Google LLC and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.storage.file;

import static org.eclipse.jgit.util.IO.readFully;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Arrays;

import org.eclipse.jgit.internal.JGitText;

/**
 * Chooses the specific implementation of the object-size index based on the
 * file version.
 */
public class PackObjectSizeIndexLoader {

    /**
     * Read an object size index from the stream
     *
     * @param in input stream at the beginning of the object size data
     * @return an implementation of the object size index
     * @throws IOException error reading the stream, empty stream or content is not an
     *                     object size index
     */
    public static PackObjectSizeIndex load(InputStream in) throws IOException {
        // Read the header (4 bytes)
        byte[] header = new byte[4];
        readFully(in, header, 0, 4);
        if (!Arrays.equals(header, PackObjectSizeIndexWriter.HEADER)) {
            throw new IOException(MessageFormat.format(
                    JGitText.get().unreadableObjectSizeIndex,
                    Integer.valueOf(header.length),
                    Arrays.toString(header)));
        }

        // Read the version (1 byte)
        int versionByte = in.read();
        if (versionByte == -1) {
            throw new EOFException("Unexpected end of stream while reading version");
        }
        int version = versionByte & 0xFF; // Ensure the value is between 0 and 255
        if (version != 1) {
            throw new IOException(MessageFormat.format(
                    JGitText.get().unsupportedObjectSizeIndexVersion,
                    Integer.valueOf(version)));
        }

        return PackObjectSizeIndexV1.parse(in);
    }

}
