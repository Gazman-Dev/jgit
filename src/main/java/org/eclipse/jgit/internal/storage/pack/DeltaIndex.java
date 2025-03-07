/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.pack;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Index of blocks in a source file.
 * <p>
 * The index can be passed a result buffer, and output an instruction sequence
 * that transforms the source buffer used by the index into the result buffer.
 * The instruction sequence can be executed by
 * {@link BinaryDelta} to recreate the
 * result buffer.
 * <p>
 * An index stores the entire contents of the source buffer, but also a table of
 * block identities mapped to locations where the block appears in the source
 * buffer. The mapping table uses 12 bytes for every 16 bytes of source buffer,
 * and is therefore ~75% of the source buffer size. The overall index is ~1.75x
 * the size of the source buffer. This relationship holds for any JVM, as only a
 * constant number of objects are allocated per index. Callers can use the
 * method {@link #getIndexSize()} to obtain a reasonably accurate estimate of
 * the complete heap space used by this index.
 * <p>
 * A {@code DeltaIndex} is thread-safe. Concurrent threads can use the same
 * index to encode delta instructions for different result buffers.
 */
public class DeltaIndex {
    /**
     * Number of bytes in a block.
     */
    static final int BLKSZ = 16; // must be 16, see unrolled loop in hashBlock

    /**
     * Estimate the size of an index for a given source.
     * <p>
     * This is roughly a worst-case estimate. The actual index may be smaller.
     *
     * @param sourceLength length of the source, in bytes.
     * @return estimated size. Approximately {@code 1.75 * sourceLength}.
     */
    public static long estimateIndexSize(int sourceLength) {
        return sourceLength + (sourceLength * 3L / 4);
    }

    /**
     * Maximum number of positions to consider for a given content hash.
     * <p>
     * All positions with the same content hash are stored into a single chain.
     * The chain size is capped to ensure delta encoding stays linear time at
     * O(len_src + len_dst) rather than quadratic at O(len_src * len_dst).
     */
    private static final int MAX_CHAIN_LENGTH = 64;

    /**
     * Original source file that we indexed.
     */
    private final byte[] src;

    /**
     * Pointers into the {@link #entries} table, indexed by block hash.
     * <p>
     * A block hash is masked with {@link #tableMask} to become the array index
     * of this table. The value stored here is the first index within
     * {@link #entries} that starts the consecutive list of blocks with that
     * same masked hash. If there are no matching blocks, 0 is stored instead.
     * <p>
     * Note that this table is always a power of 2 in size, to support fast
     * normalization of a block hash into an array index.
     */
    private final int[] table;

    /**
     * Pairs of block hash value and {@link #src} offsets.
     * <p>
     * The very first entry in this table at index 0 is always empty, this is to
     * allow fast evaluation when {@link #table} has no values under any given
     * slot. Remaining entries are pairs of integers, with the upper 32 bits
     * holding the block hash and the lower 32 bits holding the source offset.
     */
    private final long[] entries;

    /**
     * Mask to make block hashes into an array index for {@link #table}.
     */
    private final int tableMask;

    /**
     * Construct an index from the source file.
     *
     * @param sourceBuffer the source file's raw contents. The buffer will be held by the
     *                     index instance to facilitate matching, and therefore must not
     *                     be modified by the caller.
     */
    public DeltaIndex(byte[] sourceBuffer) {
        src = sourceBuffer;

        DeltaIndexScanner scan = new DeltaIndexScanner(src, src.length);

        // Reuse the same table the scanner made. We will replace the
        // values at each position, but we want the same-length array.
        //
        table = scan.table;
        tableMask = scan.tableMask;

        // Because entry index 0 means there are no entries for the
        // slot in the table, we have to allocate one extra position.
        //
        entries = new long[1 + countEntries(scan)];
        copyEntries(scan);
    }

    private int countEntries(DeltaIndexScanner scan) {
        // Figure out exactly how many entries we need. As we do the
        // enumeration truncate any delta chains longer than what we
        // are willing to scan during encode. This keeps the encode
        // logic linear in the size of the input rather than quadratic.
        //
        int cnt = 0;
        for (int element : table) {
            int h = element;
            if (h == 0)
                continue;

            int len = 0;
            do {
                if (++len == MAX_CHAIN_LENGTH) {
                    scan.next[h] = 0;
                    break;
                }
                h = scan.next[h];
            } while (h != 0);
            cnt += len;
        }
        return cnt;
    }

    private void copyEntries(DeltaIndexScanner scan) {
        // Rebuild the entries list from the scanner, positioning all
        // blocks in the same hash chain next to each other. We can
        // then later discard the next list, along with the scanner.
        //
        int next = 1;
        for (int i = 0; i < table.length; i++) {
            int h = table[i];
            if (h == 0)
                continue;

            table[i] = next;
            do {
                entries[next++] = scan.entries[h];
                h = scan.next[h];
            } while (h != 0);
        }
    }

    /**
     * Get size of the source buffer this index has scanned.
     *
     * @return size of the source buffer this index has scanned.
     */
    public long getSourceSize() {
        return src.length;
    }

    /**
     * Get an estimate of the memory required by this index.
     *
     * @return an approximation of the number of bytes used by this index in
     * memory. The size includes the cached source buffer size from
     * {@link #getSourceSize()}, as well as a rough approximation of JVM
     * object overheads.
     */
    public long getIndexSize() {
        long sz = 8 /* object header */;
        sz += 4 /* fields */ * 4 /* guessed size per field */;
        sz += sizeOf(src);
        sz += sizeOf(table);
        sz += sizeOf(entries);
        return sz;
    }

    private static long sizeOf(byte[] b) {
        return sizeOfArray(1, b.length);
    }

    private static long sizeOf(int[] b) {
        return sizeOfArray(4, b.length);
    }

    private static long sizeOf(long[] b) {
        return sizeOfArray(8, b.length);
    }

    private static int sizeOfArray(int entSize, int len) {
        return 12 /* estimated array header size */ + (len * entSize);
    }

    /**
     * Generate a delta sequence to recreate the result buffer.
     * <p>
     * There is no limit on the size of the delta sequence created. This is the
     * same as {@code encode(out, res, 0)}.
     *
     * @param out stream to receive the delta instructions that can transform
     *            this index's source buffer into {@code res}. This stream
     *            should be buffered, as instructions are written directly to it
     *            in small bursts.
     * @param res the desired result buffer. The generated instructions will
     *            recreate this buffer when applied to the source buffer stored
     *            within this index.
     * @throws IOException the output stream refused to write the instructions.
     */
    public void encode(OutputStream out, byte[] res) throws IOException {
        encode(out, res, 0 /* no limit */);
    }

    /**
     * Generate a delta sequence to recreate the result buffer.
     *
     * @param out            stream to receive the delta instructions that can transform
     *                       this index's source buffer into {@code res}. This stream
     *                       should be buffered, as instructions are written directly to it
     *                       in small bursts. If the caller might need to discard the
     *                       instructions (such as when deltaSizeLimit would be exceeded)
     *                       the caller is responsible for discarding or rewinding the
     *                       stream when this method returns false.
     * @param res            the desired result buffer. The generated instructions will
     *                       recreate this buffer when applied to the source buffer stored
     *                       within this index.
     * @param deltaSizeLimit maximum number of bytes that the delta instructions can
     *                       occupy. If the generated instructions would be longer than
     *                       this amount, this method returns false. If 0, there is no
     *                       limit on the length of delta created.
     * @return true if the delta is smaller than deltaSizeLimit; false if the
     * encoder aborted because the encoded delta instructions would be
     * longer than deltaSizeLimit bytes.
     * @throws IOException the output stream refused to write the instructions.
     */
    public boolean encode(OutputStream out, byte[] res, int deltaSizeLimit)
            throws IOException {
        final int end = res.length;
        final DeltaEncoder enc = newEncoder(out, end, deltaSizeLimit);

        // If either input is smaller than one full block, we simply punt
        // and construct a delta as a literal. This implies that any file
        // smaller than our block size is never delta encoded as the delta
        // will always be larger than the file itself would be.
        //
        if (end < BLKSZ || table.length == 0)
            return enc.insert(res);

        // Bootstrap the scan by constructing a hash for the first block
        // in the input.
        //
        int blkPtr = 0;
        int blkEnd = BLKSZ;
        int hash = hashBlock(res, 0);

        int resPtr = 0;
        while (blkEnd < end) {
            final int tableIdx = hash & tableMask;
            int entryIdx = table[tableIdx];
            if (entryIdx == 0) {
                // No matching blocks, slide forward one byte.
                //
                hash = step(hash, res[blkPtr++], res[blkEnd++]);
                continue;
            }

            // For every possible location of the current block, try to
            // extend the match out to the longest common substring.
            //
            int bestLen = -1;
            int bestPtr = -1;
            int bestNeg = 0;
            do {
                long ent = entries[entryIdx++];
                if (keyOf(ent) == hash) {
                    int neg = 0;
                    if (resPtr < blkPtr) {
                        // If we need to do an insertion, check to see if
                        // moving the starting point of the copy backwards
                        // will allow us to shorten the insert. Our hash
                        // may not have allowed us to identify this area.
                        // Since it is quite fast to perform a negative
                        // scan, try to stretch backwards too.
                        //
                        neg = blkPtr - resPtr;
                        neg = negmatch(res, blkPtr, src, valOf(ent), neg);
                    }

                    int len = neg + fwdmatch(res, blkPtr, src, valOf(ent));
                    if (bestLen < len) {
                        bestLen = len;
                        bestPtr = valOf(ent);
                        bestNeg = neg;
                    }
                } else if ((keyOf(ent) & tableMask) != tableIdx)
                    break;
            } while (bestLen < 4096 && entryIdx < entries.length);

            if (bestLen < BLKSZ) {
                // All of the locations were false positives, or the copy
                // is shorter than a block. In the latter case this won't
                // give us a very great copy instruction, so delay and try
                // at the next byte.
                //
                hash = step(hash, res[blkPtr++], res[blkEnd++]);
                continue;
            }

            blkPtr -= bestNeg;

            if (resPtr < blkPtr) {
                // There are bytes between the last instruction we made
                // and the current block pointer. None of these matched
                // during the earlier iteration so insert them directly
                // into the instruction stream.
                //
                int cnt = blkPtr - resPtr;
                if (!enc.insert(res, resPtr, cnt))
                    return false;
            }

            if (!enc.copy(bestPtr - bestNeg, bestLen))
                return false;

            blkPtr += bestLen;
            resPtr = blkPtr;
            blkEnd = blkPtr + BLKSZ;

            // If we don't have a full block available to us, abort now.
            //
            if (end <= blkEnd)
                break;

            // Start a new hash of the block after the copy region.
            //
            hash = hashBlock(res, blkPtr);
        }

        if (resPtr < end) {
            // There were bytes at the end which didn't match, or maybe
            // didn't make a full block. Insert whatever is left over.
            //
            int cnt = end - resPtr;
            return enc.insert(res, resPtr, cnt);
        }
        return true;
    }

    private DeltaEncoder newEncoder(OutputStream out, long resSize, int limit)
            throws IOException {
        return new DeltaEncoder(out, getSourceSize(), resSize, limit);
    }

    private static int fwdmatch(byte[] res, int resPtr, byte[] src, int srcPtr) {
        int start = resPtr;
        for (; resPtr < res.length && srcPtr < src.length; resPtr++, srcPtr++) {
            if (res[resPtr] != src[srcPtr])
                break;
        }
        return resPtr - start;
    }

    private static int negmatch(byte[] res, int resPtr, byte[] src, int srcPtr,
                                int limit) {
        if (srcPtr == 0)
            return 0;

        resPtr--;
        srcPtr--;
        int start = resPtr;
        do {
            if (res[resPtr] != src[srcPtr])
                break;
            resPtr--;
            srcPtr--;
        } while (0 <= srcPtr && 0 < --limit);
        return start - resPtr;
    }

    @Override
    @SuppressWarnings("nls")
    public String toString() {
        String[] units = {"bytes", "KiB", "MiB", "GiB"};
        long sz = getIndexSize();
        int u = 0;
        while (1024 <= sz && u < units.length - 1) {
            int rem = (int) (sz % 1024);
            sz /= 1024;
            if (rem != 0)
                sz++;
            u++;
        }
        return "DeltaIndex[" + sz + " " + units[u] + "]";
    }

    static int hashBlock(byte[] raw, int ptr) {
        int hash;

        // The first 4 steps collapse out into a 4 byte big-endian decode,
        // with a larger right shift as we combined shift lefts together.
        //
        hash = ((raw[ptr] & 0xff) << 24) //
                | ((raw[ptr + 1] & 0xff) << 16) //
                | ((raw[ptr + 2] & 0xff) << 8) //
                | (raw[ptr + 3] & 0xff);
        hash ^= T[hash >>> 31];

        hash = ((hash << 8) | (raw[ptr + 4] & 0xff)) ^ T[hash >>> 23];
        hash = ((hash << 8) | (raw[ptr + 5] & 0xff)) ^ T[hash >>> 23];
        hash = ((hash << 8) | (raw[ptr + 6] & 0xff)) ^ T[hash >>> 23];
        hash = ((hash << 8) | (raw[ptr + 7] & 0xff)) ^ T[hash >>> 23];

        hash = ((hash << 8) | (raw[ptr + 8] & 0xff)) ^ T[hash >>> 23];
        hash = ((hash << 8) | (raw[ptr + 9] & 0xff)) ^ T[hash >>> 23];
        hash = ((hash << 8) | (raw[ptr + 10] & 0xff)) ^ T[hash >>> 23];
        hash = ((hash << 8) | (raw[ptr + 11] & 0xff)) ^ T[hash >>> 23];

        hash = ((hash << 8) | (raw[ptr + 12] & 0xff)) ^ T[hash >>> 23];
        hash = ((hash << 8) | (raw[ptr + 13] & 0xff)) ^ T[hash >>> 23];
        hash = ((hash << 8) | (raw[ptr + 14] & 0xff)) ^ T[hash >>> 23];
        hash = ((hash << 8) | (raw[ptr + 15] & 0xff)) ^ T[hash >>> 23];

        return hash;
    }

    private static int step(int hash, byte toRemove, byte toAdd) {
        hash ^= U[toRemove & 0xff];
        return ((hash << 8) | (toAdd & 0xff)) ^ T[hash >>> 23];
    }

    private static int keyOf(long ent) {
        return (int) (ent >>> 32);
    }

    private static int valOf(long ent) {
        return (int) ent;
    }

    private static final int[] T = {0x00000000, 0xd4c6b32d, 0x7d4bd577,
            0xa98d665a, 0x2e5119c3, 0xfa97aaee, 0x531accb4, 0x87dc7f99,
            0x5ca23386, 0x886480ab, 0x21e9e6f1, 0xf52f55dc, 0x72f32a45,
            0xa6359968, 0x0fb8ff32, 0xdb7e4c1f, 0x6d82d421, 0xb944670c,
            0x10c90156, 0xc40fb27b, 0x43d3cde2, 0x97157ecf, 0x3e981895,
            0xea5eabb8, 0x3120e7a7, 0xe5e6548a, 0x4c6b32d0, 0x98ad81fd,
            0x1f71fe64, 0xcbb74d49, 0x623a2b13, 0xb6fc983e, 0x0fc31b6f,
            0xdb05a842, 0x7288ce18, 0xa64e7d35, 0x219202ac, 0xf554b181,
            0x5cd9d7db, 0x881f64f6, 0x536128e9, 0x87a79bc4, 0x2e2afd9e,
            0xfaec4eb3, 0x7d30312a, 0xa9f68207, 0x007be45d, 0xd4bd5770,
            0x6241cf4e, 0xb6877c63, 0x1f0a1a39, 0xcbcca914, 0x4c10d68d,
            0x98d665a0, 0x315b03fa, 0xe59db0d7, 0x3ee3fcc8, 0xea254fe5,
            0x43a829bf, 0x976e9a92, 0x10b2e50b, 0xc4745626, 0x6df9307c,
            0xb93f8351, 0x1f8636de, 0xcb4085f3, 0x62cde3a9, 0xb60b5084,
            0x31d72f1d, 0xe5119c30, 0x4c9cfa6a, 0x985a4947, 0x43240558,
            0x97e2b675, 0x3e6fd02f, 0xeaa96302, 0x6d751c9b, 0xb9b3afb6,
            0x103ec9ec, 0xc4f87ac1, 0x7204e2ff, 0xa6c251d2, 0x0f4f3788,
            0xdb8984a5, 0x5c55fb3c, 0x88934811, 0x211e2e4b, 0xf5d89d66,
            0x2ea6d179, 0xfa606254, 0x53ed040e, 0x872bb723, 0x00f7c8ba,
            0xd4317b97, 0x7dbc1dcd, 0xa97aaee0, 0x10452db1, 0xc4839e9c,
            0x6d0ef8c6, 0xb9c84beb, 0x3e143472, 0xead2875f, 0x435fe105,
            0x97995228, 0x4ce71e37, 0x9821ad1a, 0x31accb40, 0xe56a786d,
            0x62b607f4, 0xb670b4d9, 0x1ffdd283, 0xcb3b61ae, 0x7dc7f990,
            0xa9014abd, 0x008c2ce7, 0xd44a9fca, 0x5396e053, 0x8750537e,
            0x2edd3524, 0xfa1b8609, 0x2165ca16, 0xf5a3793b, 0x5c2e1f61,
            0x88e8ac4c, 0x0f34d3d5, 0xdbf260f8, 0x727f06a2, 0xa6b9b58f,
            0x3f0c6dbc, 0xebcade91, 0x4247b8cb, 0x96810be6, 0x115d747f,
            0xc59bc752, 0x6c16a108, 0xb8d01225, 0x63ae5e3a, 0xb768ed17,
            0x1ee58b4d, 0xca233860, 0x4dff47f9, 0x9939f4d4, 0x30b4928e,
            0xe47221a3, 0x528eb99d, 0x86480ab0, 0x2fc56cea, 0xfb03dfc7,
            0x7cdfa05e, 0xa8191373, 0x01947529, 0xd552c604, 0x0e2c8a1b,
            0xdaea3936, 0x73675f6c, 0xa7a1ec41, 0x207d93d8, 0xf4bb20f5,
            0x5d3646af, 0x89f0f582, 0x30cf76d3, 0xe409c5fe, 0x4d84a3a4,
            0x99421089, 0x1e9e6f10, 0xca58dc3d, 0x63d5ba67, 0xb713094a,
            0x6c6d4555, 0xb8abf678, 0x11269022, 0xc5e0230f, 0x423c5c96,
            0x96faefbb, 0x3f7789e1, 0xebb13acc, 0x5d4da2f2, 0x898b11df,
            0x20067785, 0xf4c0c4a8, 0x731cbb31, 0xa7da081c, 0x0e576e46,
            0xda91dd6b, 0x01ef9174, 0xd5292259, 0x7ca44403, 0xa862f72e,
            0x2fbe88b7, 0xfb783b9a, 0x52f55dc0, 0x8633eeed, 0x208a5b62,
            0xf44ce84f, 0x5dc18e15, 0x89073d38, 0x0edb42a1, 0xda1df18c,
            0x739097d6, 0xa75624fb, 0x7c2868e4, 0xa8eedbc9, 0x0163bd93,
            0xd5a50ebe, 0x52797127, 0x86bfc20a, 0x2f32a450, 0xfbf4177d,
            0x4d088f43, 0x99ce3c6e, 0x30435a34, 0xe485e919, 0x63599680,
            0xb79f25ad, 0x1e1243f7, 0xcad4f0da, 0x11aabcc5, 0xc56c0fe8,
            0x6ce169b2, 0xb827da9f, 0x3ffba506, 0xeb3d162b, 0x42b07071,
            0x9676c35c, 0x2f49400d, 0xfb8ff320, 0x5202957a, 0x86c42657,
            0x011859ce, 0xd5deeae3, 0x7c538cb9, 0xa8953f94, 0x73eb738b,
            0xa72dc0a6, 0x0ea0a6fc, 0xda6615d1, 0x5dba6a48, 0x897cd965,
            0x20f1bf3f, 0xf4370c12, 0x42cb942c, 0x960d2701, 0x3f80415b,
            0xeb46f276, 0x6c9a8def, 0xb85c3ec2, 0x11d15898, 0xc517ebb5,
            0x1e69a7aa, 0xcaaf1487, 0x632272dd, 0xb7e4c1f0, 0x3038be69,
            0xe4fe0d44, 0x4d736b1e, 0x99b5d833};

    private static final int[] U = {0x00000000, 0x12c6e90f, 0x258dd21e,
            0x374b3b11, 0x4b1ba43c, 0x59dd4d33, 0x6e967622, 0x7c509f2d,
            0x42f1fb55, 0x5037125a, 0x677c294b, 0x75bac044, 0x09ea5f69,
            0x1b2cb666, 0x2c678d77, 0x3ea16478, 0x51254587, 0x43e3ac88,
            0x74a89799, 0x666e7e96, 0x1a3ee1bb, 0x08f808b4, 0x3fb333a5,
            0x2d75daaa, 0x13d4bed2, 0x011257dd, 0x36596ccc, 0x249f85c3,
            0x58cf1aee, 0x4a09f3e1, 0x7d42c8f0, 0x6f8421ff, 0x768c3823,
            0x644ad12c, 0x5301ea3d, 0x41c70332, 0x3d979c1f, 0x2f517510,
            0x181a4e01, 0x0adca70e, 0x347dc376, 0x26bb2a79, 0x11f01168,
            0x0336f867, 0x7f66674a, 0x6da08e45, 0x5aebb554, 0x482d5c5b,
            0x27a97da4, 0x356f94ab, 0x0224afba, 0x10e246b5, 0x6cb2d998,
            0x7e743097, 0x493f0b86, 0x5bf9e289, 0x655886f1, 0x779e6ffe,
            0x40d554ef, 0x5213bde0, 0x2e4322cd, 0x3c85cbc2, 0x0bcef0d3,
            0x190819dc, 0x39dec36b, 0x2b182a64, 0x1c531175, 0x0e95f87a,
            0x72c56757, 0x60038e58, 0x5748b549, 0x458e5c46, 0x7b2f383e,
            0x69e9d131, 0x5ea2ea20, 0x4c64032f, 0x30349c02, 0x22f2750d,
            0x15b94e1c, 0x077fa713, 0x68fb86ec, 0x7a3d6fe3, 0x4d7654f2,
            0x5fb0bdfd, 0x23e022d0, 0x3126cbdf, 0x066df0ce, 0x14ab19c1,
            0x2a0a7db9, 0x38cc94b6, 0x0f87afa7, 0x1d4146a8, 0x6111d985,
            0x73d7308a, 0x449c0b9b, 0x565ae294, 0x4f52fb48, 0x5d941247,
            0x6adf2956, 0x7819c059, 0x04495f74, 0x168fb67b, 0x21c48d6a,
            0x33026465, 0x0da3001d, 0x1f65e912, 0x282ed203, 0x3ae83b0c,
            0x46b8a421, 0x547e4d2e, 0x6335763f, 0x71f39f30, 0x1e77becf,
            0x0cb157c0, 0x3bfa6cd1, 0x293c85de, 0x556c1af3, 0x47aaf3fc,
            0x70e1c8ed, 0x622721e2, 0x5c86459a, 0x4e40ac95, 0x790b9784,
            0x6bcd7e8b, 0x179de1a6, 0x055b08a9, 0x321033b8, 0x20d6dab7,
            0x73bd86d6, 0x617b6fd9, 0x563054c8, 0x44f6bdc7, 0x38a622ea,
            0x2a60cbe5, 0x1d2bf0f4, 0x0fed19fb, 0x314c7d83, 0x238a948c,
            0x14c1af9d, 0x06074692, 0x7a57d9bf, 0x689130b0, 0x5fda0ba1,
            0x4d1ce2ae, 0x2298c351, 0x305e2a5e, 0x0715114f, 0x15d3f840,
            0x6983676d, 0x7b458e62, 0x4c0eb573, 0x5ec85c7c, 0x60693804,
            0x72afd10b, 0x45e4ea1a, 0x57220315, 0x2b729c38, 0x39b47537,
            0x0eff4e26, 0x1c39a729, 0x0531bef5, 0x17f757fa, 0x20bc6ceb,
            0x327a85e4, 0x4e2a1ac9, 0x5cecf3c6, 0x6ba7c8d7, 0x796121d8,
            0x47c045a0, 0x5506acaf, 0x624d97be, 0x708b7eb1, 0x0cdbe19c,
            0x1e1d0893, 0x29563382, 0x3b90da8d, 0x5414fb72, 0x46d2127d,
            0x7199296c, 0x635fc063, 0x1f0f5f4e, 0x0dc9b641, 0x3a828d50,
            0x2844645f, 0x16e50027, 0x0423e928, 0x3368d239, 0x21ae3b36,
            0x5dfea41b, 0x4f384d14, 0x78737605, 0x6ab59f0a, 0x4a6345bd,
            0x58a5acb2, 0x6fee97a3, 0x7d287eac, 0x0178e181, 0x13be088e,
            0x24f5339f, 0x3633da90, 0x0892bee8, 0x1a5457e7, 0x2d1f6cf6,
            0x3fd985f9, 0x43891ad4, 0x514ff3db, 0x6604c8ca, 0x74c221c5,
            0x1b46003a, 0x0980e935, 0x3ecbd224, 0x2c0d3b2b, 0x505da406,
            0x429b4d09, 0x75d07618, 0x67169f17, 0x59b7fb6f, 0x4b711260,
            0x7c3a2971, 0x6efcc07e, 0x12ac5f53, 0x006ab65c, 0x37218d4d,
            0x25e76442, 0x3cef7d9e, 0x2e299491, 0x1962af80, 0x0ba4468f,
            0x77f4d9a2, 0x653230ad, 0x52790bbc, 0x40bfe2b3, 0x7e1e86cb,
            0x6cd86fc4, 0x5b9354d5, 0x4955bdda, 0x350522f7, 0x27c3cbf8,
            0x1088f0e9, 0x024e19e6, 0x6dca3819, 0x7f0cd116, 0x4847ea07,
            0x5a810308, 0x26d19c25, 0x3417752a, 0x035c4e3b, 0x119aa734,
            0x2f3bc34c, 0x3dfd2a43, 0x0ab61152, 0x1870f85d, 0x64206770,
            0x76e68e7f, 0x41adb56e, 0x536b5c61};
}
