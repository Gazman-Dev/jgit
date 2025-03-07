/*
 * Copyright (C) 2010, Google Inc.
 * Copyright (C) 2010, Matthias Sohn <matthias.sohn@sap.com>
 * Copyright (C) 2010, Jens Baumgart <jens.baumgart@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.util.FS.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.text.MessageFormat;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * File Utilities
 */
public class FileUtils {
    private static final Logger LOG = LoggerFactory.getLogger(FileUtils.class);

    private static final Random RNG = new Random();

    /**
     * Option to delete given {@code File}
     */
    public static final int NONE = 0;

    /**
     * Option to recursively delete given {@code File}
     */
    public static final int RECURSIVE = 1;

    /**
     * Option to retry deletion if not successful
     */
    public static final int RETRY = 2;

    /**
     * Option to skip deletion if file doesn't exist
     */
    public static final int SKIP_MISSING = 4;

    /**
     * Option not to throw exceptions when a deletion finally doesn't succeed.
     *
     * @since 2.0
     */
    public static final int IGNORE_ERRORS = 8;

    /**
     * Option to only delete empty directories. This option can be combined with
     * {@link #RECURSIVE}
     *
     * @since 3.0
     */
    public static final int EMPTY_DIRECTORIES_ONLY = 16;

    /**
     * Safe conversion from {@link File} to {@link Path}.
     *
     * @param f {@code File} to be converted to {@code Path}
     * @return the path represented by the file
     * @throws IOException in case the path represented by the file is not valid (
     *                     {@link InvalidPathException})
     * @since 4.10
     */
    public static Path toPath(File f) throws IOException {
        try {
            return f.toPath();
        } catch (InvalidPathException ex) {
            throw new IOException(ex);
        }
    }

    /**
     * Delete file or empty folder
     *
     * @param f {@code File} to be deleted
     * @throws IOException if deletion of {@code f} fails. This may occur if {@code f}
     *                     didn't exist when the method was called. This can therefore
     *                     cause java.io.IOExceptions during race conditions when
     *                     multiple concurrent threads all try to delete the same file.
     */
    public static void delete(File f) throws IOException {
        delete(f, NONE);
    }

    /**
     * Delete file or folder
     *
     * @param f       {@code File} to be deleted
     * @param options deletion options, {@code RECURSIVE} for recursive deletion of
     *                a subtree, {@code RETRY} to retry when deletion failed.
     *                Retrying may help if the underlying file system doesn't allow
     *                deletion of files being read by another thread.
     * @throws IOException if deletion of {@code f} fails. This may occur if {@code f}
     *                     didn't exist when the method was called. This can therefore
     *                     cause java.io.IOExceptions during race conditions when
     *                     multiple concurrent threads all try to delete the same file.
     *                     This exception is not thrown when IGNORE_ERRORS is set.
     */
    public static void delete(File f, int options) throws IOException {
        FS fs = FS.DETECTED;
        if ((options & SKIP_MISSING) != 0 && !fs.exists(f))
            return;

        if ((options & RECURSIVE) != 0 && fs.isDirectory(f)) {
            final File[] items = f.listFiles();
            if (items != null) {
                List<File> files = new ArrayList<>();
                List<File> dirs = new ArrayList<>();
                for (File c : items)
                    if (c.isFile())
                        files.add(c);
                    else
                        dirs.add(c);
                // Try to delete files first, otherwise options
                // EMPTY_DIRECTORIES_ONLY|RECURSIVE will delete empty
                // directories before aborting, depending on order.
                for (File file : files)
                    delete(file, options);
                for (File d : dirs)
                    delete(d, options);
            }
        }

        boolean delete = false;
        if ((options & EMPTY_DIRECTORIES_ONLY) != 0) {
            if (f.isDirectory()) {
                delete = true;
            } else if ((options & IGNORE_ERRORS) == 0) {
                throw new IOException(MessageFormat.format(
                        JGitText.get().deleteFileFailed, f.getAbsolutePath()));
            }
        } else {
            delete = true;
        }

        if (delete) {
            IOException t = null;
            Path p = f.toPath();
            boolean tryAgain;
            do {
                tryAgain = false;
                try {
                    Files.delete(p);
                    return;
                } catch (NoSuchFileException | FileNotFoundException e) {
                    handleDeleteException(f, e, options,
                            SKIP_MISSING | IGNORE_ERRORS);
                    return;
                } catch (DirectoryNotEmptyException e) {
                    handleDeleteException(f, e, options, IGNORE_ERRORS);
                    return;
                } catch (IOException e) {
                    if (!f.canWrite()) {
                        tryAgain = f.setWritable(true);
                    }
                    if (!tryAgain) {
                        t = e;
                    }
                }
            } while (tryAgain);

            if ((options & RETRY) != 0) {
                for (int i = 1; i < 10; i++) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) {
                        // ignore
                    }
                    try {
                        Files.deleteIfExists(p);
                        return;
                    } catch (IOException e) {
                        t = e;
                    }
                }
            }
            handleDeleteException(f, t, options, IGNORE_ERRORS);
        }
    }

    private static void handleDeleteException(File f, IOException e,
                                              int allOptions, int checkOptions) throws IOException {
        if (e != null && (allOptions & checkOptions) == 0) {
            throw new IOException(MessageFormat.format(
                    JGitText.get().deleteFileFailed, f.getAbsolutePath()), e);
        }
    }

    /**
     * Rename a file or folder. If the rename fails and if we are running on a
     * filesystem where it makes sense to repeat a failing rename then repeat
     * the rename operation up to 9 times with 100ms sleep time between two
     * calls. Furthermore if the destination exists and is directory hierarchy
     * with only directories in it, the whole directory hierarchy will be
     * deleted. If the target represents a non-empty directory structure, empty
     * subdirectories within that structure may or may not be deleted even if
     * the method fails. Furthermore if the destination exists and is a file
     * then the file will be deleted and then the rename is retried.
     * <p>
     * This operation is <em>not</em> atomic.
     *
     * @param src the old {@code File}
     * @param dst the new {@code File}
     * @throws IOException if the rename has failed
     * @see FS#retryFailedLockFileCommit()
     * @since 3.0
     */
    public static void rename(File src, File dst)
            throws IOException {
        rename(src, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Rename a file or folder using the passed
     * {@link CopyOption}s. If the rename fails and if we are
     * running on a filesystem where it makes sense to repeat a failing rename
     * then repeat the rename operation up to 9 times with 100ms sleep time
     * between two calls. Furthermore if the destination exists and is a
     * directory hierarchy with only directories in it, the whole directory
     * hierarchy will be deleted. If the target represents a non-empty directory
     * structure, empty subdirectories within that structure may or may not be
     * deleted even if the method fails. Furthermore if the destination exists
     * and is a file then the file will be replaced if
     * {@link StandardCopyOption#REPLACE_EXISTING} has been set.
     * If {@link StandardCopyOption#ATOMIC_MOVE} has been set the
     * rename will be done atomically or fail with an
     * {@link AtomicMoveNotSupportedException}
     *
     * @param src     the old file
     * @param dst     the new file
     * @param options options to pass to
     *                {@link Files#move(Path, Path, CopyOption...)}
     * @throws AtomicMoveNotSupportedException if file cannot be moved as an atomic file system operation
     * @throws IOException                     if an IO error occurred
     * @since 4.1
     */
    public static void rename(final File src, final File dst,
                              CopyOption... options)
            throws AtomicMoveNotSupportedException, IOException {
        int attempts = FS.DETECTED.retryFailedLockFileCommit() ? 10 : 1;
        IOException finalError = null;
        while (--attempts >= 0) {
            try {
                Files.move(toPath(src), toPath(dst), options);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                throw e;
            } catch (IOException e) {
                if (attempts == 0) {
                    // Only delete on the last attempt.
                    try {
                        if (!dst.delete()) {
                            delete(dst, EMPTY_DIRECTORIES_ONLY | RECURSIVE);
                        }
                        // On *nix there is no try, you do or do not
                        Files.move(toPath(src), toPath(dst), options);
                        return;
                    } catch (IOException e2) {
                        e2.addSuppressed(e);
                        finalError = e2;
                    }
                }
            }
            if (attempts > 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new IOException(MessageFormat.format(
                            JGitText.get().renameFileFailed,
                            src.getAbsolutePath(), dst.getAbsolutePath()), e);
                }
            }
        }
        throw new IOException(
                MessageFormat.format(JGitText.get().renameFileFailed,
                        src.getAbsolutePath(), dst.getAbsolutePath()),
                finalError);
    }

    /**
     * Creates the directory named by this abstract pathname.
     *
     * @param d directory to be created
     * @throws IOException if creation of {@code d} fails. This may occur if {@code d}
     *                     did exist when the method was called. This can therefore
     *                     cause java.io.IOExceptions during race conditions when
     *                     multiple concurrent threads all try to create the same
     *                     directory.
     */
    public static void mkdir(File d)
            throws IOException {
        mkdir(d, false);
    }

    /**
     * Creates the directory named by this abstract pathname.
     *
     * @param d            directory to be created
     * @param skipExisting if {@code true} skip creation of the given directory if it
     *                     already exists in the file system
     * @throws IOException if creation of {@code d} fails. This may occur if {@code d}
     *                     did exist when the method was called. This can therefore
     *                     cause java.io.IOExceptions during race conditions when
     *                     multiple concurrent threads all try to create the same
     *                     directory.
     */
    public static void mkdir(File d, boolean skipExisting)
            throws IOException {
        if (!d.mkdir()) {
            if (skipExisting && d.isDirectory())
                return;
            throw new IOException(MessageFormat.format(
                    JGitText.get().mkDirFailed, d.getAbsolutePath()));
        }
    }

    /**
     * Creates the directory named by this abstract pathname, including any
     * necessary but nonexistent parent directories. Note that if this operation
     * fails it may have succeeded in creating some of the necessary parent
     * directories.
     *
     * @param d directory to be created
     * @throws IOException if creation of {@code d} fails. This may occur if {@code d}
     *                     did exist when the method was called. This can therefore
     *                     cause java.io.IOExceptions during race conditions when
     *                     multiple concurrent threads all try to create the same
     *                     directory.
     */
    public static void mkdirs(File d) throws IOException {
        mkdirs(d, false);
    }

    /**
     * Creates the directory named by this abstract pathname, including any
     * necessary but nonexistent parent directories. Note that if this operation
     * fails it may have succeeded in creating some of the necessary parent
     * directories.
     *
     * @param d            directory to be created
     * @param skipExisting if {@code true} skip creation of the given directory if it
     *                     already exists in the file system
     * @throws IOException if creation of {@code d} fails. This may occur if {@code d}
     *                     did exist when the method was called. This can therefore
     *                     cause java.io.IOExceptions during race conditions when
     *                     multiple concurrent threads all try to create the same
     *                     directory.
     */
    public static void mkdirs(File d, boolean skipExisting)
            throws IOException {
        if (!d.mkdirs()) {
            if (skipExisting && d.isDirectory())
                return;
            throw new IOException(MessageFormat.format(
                    JGitText.get().mkDirsFailed, d.getAbsolutePath()));
        }
    }

    /**
     * Atomically creates a new, empty file named by this abstract pathname if
     * and only if a file with this name does not yet exist. The check for the
     * existence of the file and the creation of the file if it does not exist
     * are a single operation that is atomic with respect to all other
     * filesystem activities that might affect the file.
     * <p>
     * Note: this method should not be used for file-locking, as the resulting
     * protocol cannot be made to work reliably. The
     * {@link java.nio.channels.FileLock} facility should be used instead.
     *
     * @param f the file to be created
     * @throws IOException if the named file already exists or if an I/O error occurred
     */
    public static void createNewFile(File f) throws IOException {
        if (!f.createNewFile())
            throw new IOException(MessageFormat.format(
                    JGitText.get().createNewFileFailed, f));
    }

    /**
     * Create a symbolic link
     *
     * @param path   the path of the symbolic link to create
     * @param target the target of the symbolic link
     * @return the path to the symbolic link
     * @throws IOException if an IO error occurred
     * @since 4.2
     */
    public static Path createSymLink(File path, String target)
            throws IOException {
        Path nioPath = toPath(path);
        if (Files.exists(nioPath, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attrs = Files.readAttributes(nioPath,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isRegularFile() || attrs.isSymbolicLink()) {
                delete(path);
            } else {
                delete(path, EMPTY_DIRECTORIES_ONLY | RECURSIVE);
            }
        }
        if (SystemReader.getInstance().isWindows()) {
            target = target.replace('/', '\\');
        }
        Path nioTarget = toPath(new File(target));
        return Files.createSymbolicLink(nioPath, nioTarget);
    }

    /**
     * Read target path of the symlink.
     *
     * @param path a {@link File} object.
     * @return target path of the symlink, or null if it is not a symbolic link
     * @throws IOException if an IO error occurred
     * @since 3.0
     */
    public static String readSymLink(File path) throws IOException {
        Path nioPath = toPath(path);
        Path target = Files.readSymbolicLink(nioPath);
        String targetString = target.toString();
        if (SystemReader.getInstance().isWindows()) {
            targetString = targetString.replace('\\', '/');
        } else if (SystemReader.getInstance().isMacOS()) {
            targetString = Normalizer.normalize(targetString, Form.NFC);
        }
        return targetString;
    }

    /**
     * Create a temporary directory.
     *
     * @param prefix prefix string
     * @param suffix suffix string
     * @param dir    The parent dir, can be null to use system default temp dir.
     * @return the temp dir created.
     * @throws IOException if an IO error occurred
     * @since 3.4
     */
    public static File createTempDir(String prefix, String suffix, File dir)
            throws IOException {
        final int RETRIES = 1; // When something bad happens, retry once.
        for (int i = 0; i < RETRIES; i++) {
            File tmp = File.createTempFile(prefix, suffix, dir);
            if (!tmp.delete())
                continue;
            if (!tmp.mkdir())
                continue;
            return tmp;
        }
        throw new IOException(JGitText.get().cannotCreateTempDir);
    }

    /**
     * Expresses <code>other</code> as a relative file path from
     * <code>base</code>. File-separator and case sensitivity are based on the
     * current file system.
     * <p>
     * See also
     * {@link FileUtils#relativizePath(String, String, String, boolean)}.
     *
     * @param base  Base path
     * @param other Destination path
     * @return Relative path from <code>base</code> to <code>other</code>
     * @since 4.8
     */
    public static String relativizeNativePath(String base, String other) {
        return FS.DETECTED.relativize(base, other);
    }

    /**
     * Expresses <code>other</code> as a relative file path from
     * <code>base</code>. File-separator and case sensitivity are based on Git's
     * internal representation of files (which matches Unix).
     * <p>
     * See also
     * {@link FileUtils#relativizePath(String, String, String, boolean)}.
     *
     * @param base  Base path
     * @param other Destination path
     * @return Relative path from <code>base</code> to <code>other</code>
     * @since 4.8
     */
    public static String relativizeGitPath(String base, String other) {
        return relativizePath(base, other, "/", false); //$NON-NLS-1$
    }


    /**
     * Expresses <code>other</code> as a relative file path from <code>base</code>
     * <p>
     * For example, if called with the two following paths :
     *
     * <pre>
     * <code>base = "c:\\Users\\jdoe\\eclipse\\git\\project"</code>
     * <code>other = "c:\\Users\\jdoe\\eclipse\\git\\another_project\\pom.xml"</code>
     * </pre>
     * <p>
     * This will return "..\\another_project\\pom.xml".
     *
     * <p>
     * <b>Note</b> that this will return the empty String if <code>base</code>
     * and <code>other</code> are equal.
     * </p>
     *
     * @param base          The path against which <code>other</code> should be
     *                      relativized. This will be assumed to denote the path to a
     *                      folder and not a file.
     * @param other         The path that will be made relative to <code>base</code>.
     * @param dirSeparator  A string that separates components of the path. In practice, this is "/" or "\\".
     * @param caseSensitive Whether to consider differently-cased directory names as distinct
     * @return A relative path that, when resolved against <code>base</code>,
     * will yield the original <code>other</code>.
     * @since 4.8
     */
    public static String relativizePath(String base, String other, String dirSeparator, boolean caseSensitive) {
        if (base.equals(other))
            return ""; //$NON-NLS-1$

        final String[] baseSegments = base.split(Pattern.quote(dirSeparator));
        final String[] otherSegments = other.split(Pattern
                .quote(dirSeparator));

        int commonPrefix = 0;
        while (commonPrefix < baseSegments.length
                && commonPrefix < otherSegments.length) {
            if (caseSensitive
                    && baseSegments[commonPrefix]
                    .equals(otherSegments[commonPrefix]))
                commonPrefix++;
            else if (!caseSensitive
                    && baseSegments[commonPrefix]
                    .equalsIgnoreCase(otherSegments[commonPrefix]))
                commonPrefix++;
            else
                break;
        }

        final StringBuilder builder = new StringBuilder();
        for (int i = commonPrefix; i < baseSegments.length; i++)
            builder.append("..").append(dirSeparator); //$NON-NLS-1$
        for (int i = commonPrefix; i < otherSegments.length; i++) {
            builder.append(otherSegments[i]);
            if (i < otherSegments.length - 1)
                builder.append(dirSeparator);
        }
        return builder.toString();
    }

    /**
     * Determine if an IOException is a stale NFS file handle
     *
     * @param ioe an {@link IOException} object.
     * @return a boolean true if the IOException is a stale NFS file handle
     * @since 4.1
     */
    public static boolean isStaleFileHandle(IOException ioe) {
        String msg = ioe.getMessage();
        return msg != null
                && msg.toLowerCase(Locale.ROOT)
                .matches("stale .*file .*handle"); //$NON-NLS-1$
    }

    /**
     * Determine if a throwable or a cause in its causal chain is a stale NFS
     * file handle
     *
     * @param throwable a {@link Throwable} object.
     * @return a boolean true if the throwable or a cause in its causal chain is
     * a stale NFS file handle
     * @since 4.7
     */
    public static boolean isStaleFileHandleInCausalChain(Throwable throwable) {
        while (throwable != null) {
            if (throwable instanceof IOException
                    && isStaleFileHandle((IOException) throwable)) {
                return true;
            }
            throwable = throwable.getCause();
        }
        return false;
    }

    /**
     * Like a {@link java.util.function.Function} but throwing an
     * {@link Exception}.
     *
     * @param <A> input type
     * @param <B> output type
     * @since 6.2
     */
    @FunctionalInterface
    public interface IOFunction<A, B> {

        /**
         * Performs the function.
         *
         * @param t input to operate on
         * @return the output
         * @throws Exception if a problem occurs
         */
        B apply(A t) throws Exception;
    }

    private static void backOff(long delay, IOException cause)
            throws IOException {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            IOException interruption = new InterruptedIOException();
            interruption.initCause(e);
            interruption.addSuppressed(cause);
            Thread.currentThread().interrupt(); // Re-set flag
            throw interruption;
        }
    }

    /**
     * Invokes the given {@link IOFunction}, performing a limited number of
     * re-tries if exceptions occur that indicate either a stale NFS file handle
     * or that indicate that the file may be written concurrently.
     *
     * @param <T>    result type
     * @param file   to read
     * @param reader for reading the file and creating an instance of {@code T}
     * @return the result of the {@code reader}, or {@code null} if the file
     * does not exist
     * @throws Exception if a problem occurs
     * @since 6.2
     */
    public static <T> T readWithRetries(File file,
                                        IOFunction<File, ? extends T> reader)
            throws Exception {
        int maxStaleRetries = 5;
        int retries = 0;
        long backoff = 50;
        while (true) {
            try {
                try {
                    return reader.apply(file);
                } catch (IOException e) {
                    if (FileUtils.isStaleFileHandleInCausalChain(e)
                            && retries < maxStaleRetries) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug(MessageFormat.format(
                                    JGitText.get().packedRefsHandleIsStale,
                                    Integer.valueOf(retries)), e);
                        }
                        retries++;
                        continue;
                    }
                    throw e;
                }
            } catch (FileNotFoundException noFile) {
                if (!file.isFile()) {
                    return null;
                }
                // Probably Windows and some other thread is writing the file
                // concurrently.
                if (backoff > 1000) {
                    throw noFile;
                }
                backOff(backoff, noFile);
                backoff *= 2; // 50, 100, 200, 400, 800 ms
            }
        }
    }

    /**
     * Check if file is a symlink
     *
     * @param file the file to be checked if it is a symbolic link
     * @return {@code true} if the passed file is a symbolic link
     */
    static boolean isSymlink(File file) {
        return Files.isSymbolicLink(file.toPath());
    }

    /**
     * Get the lastModified attribute for a given file
     *
     * @param file the file
     * @return lastModified attribute for given file, not following symbolic
     * links
     * @throws IOException if an IO error occurred
     * @deprecated use {@link #lastModifiedInstant(Path)} instead which returns
     * FileTime
     */
    @Deprecated
    static long lastModified(File file) throws IOException {
        return Files.getLastModifiedTime(toPath(file), LinkOption.NOFOLLOW_LINKS)
                .toMillis();
    }

    /**
     * Get last modified timestamp of a file
     *
     * @param path file path
     * @return lastModified attribute for given file, not following symbolic
     * links
     */
    static Instant lastModifiedInstant(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                    .toInstant();
        } catch (NoSuchFileException e) {
            LOG.debug(
                    "Cannot read lastModifiedInstant since path {} does not exist", //$NON-NLS-1$
                    path);
            return Instant.EPOCH;
        } catch (IOException e) {
            LOG.error(MessageFormat
                    .format(JGitText.get().readLastModifiedFailed, path), e);
            return Instant.ofEpochMilli(path.toFile().lastModified());
        }
    }

    /**
     * Return all the attributes of a file, without following symbolic links.
     *
     * @param file the file
     * @return {@link BasicFileAttributes} of the file
     * @throws IOException in case of any I/O errors accessing the file
     * @since 4.5.6
     */
    static BasicFileAttributes fileAttributes(File file) throws IOException {
        return Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Set the last modified time of a file system object.
     *
     * @param file the file
     * @param time last modified timestamp
     * @throws IOException if an IO error occurred
     */
    @Deprecated
    static void setLastModified(File file, long time) throws IOException {
        Files.setLastModifiedTime(toPath(file), FileTime.fromMillis(time));
    }

    /**
     * Set the last modified time of a file system object.
     *
     * @param path file path
     * @param time last modified timestamp of the file
     * @throws IOException if an IO error occurred
     */
    static void setLastModified(Path path, Instant time)
            throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(time));
    }

    /**
     * Whether the file exists
     *
     * @param file the file
     * @return {@code true} if the given file exists, not following symbolic
     * links
     */
    static boolean exists(File file) {
        return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Check if file is hidden (on Windows)
     *
     * @param file the file
     * @return {@code true} if the given file is hidden
     * @throws IOException if an IO error occurred
     */
    static boolean isHidden(File file) throws IOException {
        return Files.isHidden(toPath(file));
    }

    /**
     * Set a file hidden (on Windows)
     *
     * @param file   a {@link File} object.
     * @param hidden a boolean.
     * @throws IOException if an IO error occurred
     * @since 4.1
     */
    public static void setHidden(File file, boolean hidden) throws IOException {
        Files.setAttribute(toPath(file), "dos:hidden", Boolean.valueOf(hidden), //$NON-NLS-1$
                LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Get file length
     *
     * @param file a {@link File}.
     * @return length of the given file
     * @throws IOException if an IO error occurred
     * @since 4.1
     */
    public static long getLength(File file) throws IOException {
        Path nioPath = toPath(file);
        if (Files.isSymbolicLink(nioPath))
            return Files.readSymbolicLink(nioPath).toString()
                    .getBytes(UTF_8).length;
        return Files.size(nioPath);
    }

    /**
     * Check if file is directory
     *
     * @param file the file
     * @return {@code true} if the given file is a directory, not following
     * symbolic links
     */
    static boolean isDirectory(File file) {
        return Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Check if File is a file
     *
     * @param file the file
     * @return {@code true} if the given file is a file, not following symbolic
     * links
     */
    static boolean isFile(File file) {
        return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Whether the path is a directory with files in it.
     *
     * @param dir directory path
     * @return {@code true} if the given directory path contains files
     * @throws IOException on any I/O errors accessing the path
     * @since 5.11
     */
    public static boolean hasFiles(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.findAny().isPresent();
        }
    }

    /**
     * Whether the given file can be executed.
     *
     * @param file a {@link File} object.
     * @return {@code true} if the given file can be executed.
     * @since 4.1
     */
    public static boolean canExecute(File file) {
        if (!isFile(file)) {
            return false;
        }
        return Files.isExecutable(file.toPath());
    }

    /**
     * Get basic file attributes
     *
     * @param fs   a {@link FS} object.
     * @param file the file
     * @return non null attributes object
     */
    static Attributes getFileAttributesBasic(FS fs, File file) {
        try {
            Path nioPath = toPath(file);
            BasicFileAttributes readAttributes = nioPath
                    .getFileSystem()
                    .provider()
                    .getFileAttributeView(nioPath,
                            BasicFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS).readAttributes();
            Attributes attributes = new Attributes(fs, file,
                    true,
                    readAttributes.isDirectory(),
                    fs.supportsExecute() ? file.canExecute() : false,
                    readAttributes.isSymbolicLink(),
                    readAttributes.isRegularFile(), //
                    readAttributes.creationTime().toMillis(), //
                    readAttributes.lastModifiedTime().toInstant(),
                    readAttributes.isSymbolicLink() ? Constants
                            .encode(readSymLink(file)).length
                            : readAttributes.size());
            return attributes;
        } catch (IOException e) {
            return new Attributes(file, fs);
        }
    }

    /**
     * Get file system attributes for the given file.
     *
     * @param fs   a {@link FS} object.
     * @param file a {@link File}.
     * @return file system attributes for the given file.
     * @since 4.1
     */
    public static Attributes getFileAttributesPosix(FS fs, File file) {
        try {
            Path nioPath = toPath(file);
            PosixFileAttributes readAttributes = nioPath
                    .getFileSystem()
                    .provider()
                    .getFileAttributeView(nioPath,
                            PosixFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS).readAttributes();
            Attributes attributes = new Attributes(
                    fs,
                    file,
                    true, //
                    readAttributes.isDirectory(), //
                    readAttributes.permissions().contains(
                            PosixFilePermission.OWNER_EXECUTE),
                    readAttributes.isSymbolicLink(),
                    readAttributes.isRegularFile(), //
                    readAttributes.creationTime().toMillis(), //
                    readAttributes.lastModifiedTime().toInstant(),
                    readAttributes.size());
            return attributes;
        } catch (IOException e) {
            return new Attributes(file, fs);
        }
    }

    /**
     * NFC normalize a file (on Mac), otherwise do nothing
     *
     * @param file a {@link File}.
     * @return on Mac: NFC normalized {@link File}, otherwise the passed
     * file
     * @since 4.1
     */
    public static File normalize(File file) {
        if (SystemReader.getInstance().isMacOS()) {
            // TODO: Would it be faster to check with isNormalized first
            // assuming normalized paths are much more common
            String normalized = Normalizer.normalize(file.getPath(),
                    Form.NFC);
            return new File(normalized);
        }
        return file;
    }

    /**
     * On Mac: get NFC normalized form of given name, otherwise the given name.
     *
     * @param name a {@link String} object.
     * @return on Mac: NFC normalized form of given name
     * @since 4.1
     */
    public static String normalize(String name) {
        if (SystemReader.getInstance().isMacOS()) {
            if (name == null)
                return null;
            return Normalizer.normalize(name, Form.NFC);
        }
        return name;
    }

    /**
     * Best-effort variation of {@link File#getCanonicalFile()}
     * returning the input file if the file cannot be canonicalized instead of
     * throwing {@link IOException}.
     *
     * @param file to be canonicalized; may be {@code null}
     * @return canonicalized file, or the unchanged input file if
     * canonicalization failed or if {@code file == null}
     * @throws SecurityException if {@link File#getCanonicalFile()} throws one
     * @since 4.2
     */
    public static File canonicalize(File file) {
        if (file == null) {
            return null;
        }
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            return file;
        }
    }

    /**
     * Convert a path to String, replacing separators as necessary.
     *
     * @param file a {@link File}.
     * @return file's path as a String
     * @since 4.10
     */
    public static String pathToString(File file) {
        final String path = file.getPath();
        if (SystemReader.getInstance().isWindows()) {
            return path.replace('\\', '/');
        }
        return path;
    }

    /**
     * Touch the given file
     *
     * @param f the file to touch
     * @throws IOException if an IO error occurred
     * @since 5.1.8
     */
    public static void touch(Path f) throws IOException {
        try (FileChannel fc = FileChannel.open(f, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND, StandardOpenOption.SYNC)) {
            // touch
        }
        Files.setLastModifiedTime(f, FileTime.from(Instant.now()));
    }

    /**
     * Compute a delay in a {@code min..max} interval with random jitter.
     *
     * @param last amount of delay waited before the last attempt. This is used
     *             to seed the next delay interval. Should be 0 if there was no
     *             prior delay.
     * @param min  shortest amount of allowable delay between attempts.
     * @param max  longest amount of allowable delay between attempts.
     * @return new amount of delay to wait before the next attempt.
     * @since 5.6
     */
    public static long delay(long last, long min, long max) {
        long r = Math.max(0, last * 3 - min);
        if (r > 0) {
            int c = (int) Math.min(r + 1, Integer.MAX_VALUE);
            r = RNG.nextInt(c);
        }
        return Math.max(Math.min(min + r, max), min);
    }
}
