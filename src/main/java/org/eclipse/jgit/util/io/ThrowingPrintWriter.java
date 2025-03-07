/*
 * Copyright (C) 2012, Robin Rosenberg <robin.rosenberg@dewire.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util.io;

import org.eclipse.jgit.util.SystemReader;

import java.io.IOException;
import java.io.Writer;
import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * An alternative PrintWriter that doesn't catch exceptions.
 *
 * @since 2.2
 */
public class ThrowingPrintWriter extends Writer {

    private final Writer out;

    private final String LF;

    /**
     * Construct a JGitPrintWriter
     *
     * @param out the underlying {@link Writer}
     */
    public ThrowingPrintWriter(Writer out) {
        this.out = out;
        LF = AccessController
                .doPrivileged((PrivilegedAction<String>) () -> SystemReader
                        .getInstance().getProperty("line.separator") //$NON-NLS-1$
                );
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        out.write(cbuf, off, len);
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    /**
     * Print a string and terminate with a line feed.
     *
     * @param s a {@link String} object.
     * @throws IOException if an IO error occurred
     */
    public void println(String s) throws IOException {
        print(s + LF);
    }

    /**
     * Print a platform dependent new line
     *
     * @throws IOException if an IO error occurred
     */
    public void println() throws IOException {
        print(LF);
    }

    /**
     * Print a char
     *
     * @param value a char.
     * @throws IOException if an IO error occurred
     */
    public void print(char value) throws IOException {
        print(String.valueOf(value));
    }

    /**
     * Print an int as string
     *
     * @param value an int.
     * @throws IOException if an IO error occurred
     */
    public void print(int value) throws IOException {
        print(String.valueOf(value));
    }

    /**
     * Print a long as string
     *
     * @param value a long.
     * @throws IOException if an IO error occurred
     */
    public void print(long value) throws IOException {
        print(String.valueOf(value));
    }

    /**
     * Print a short as string
     *
     * @param value a short.
     * @throws IOException if an IO error occurred
     */
    public void print(short value) throws IOException {
        print(String.valueOf(value));
    }

    /**
     * Print a formatted message according to
     * {@link String#format(String, Object...)}.
     *
     * @param fmt  a {@link String} object.
     * @param args objects.
     * @throws IOException if an IO error occurred
     */
    public void format(String fmt, Object... args) throws IOException {
        print(String.format(fmt, args));
    }

    /**
     * Print an object's toString representations
     *
     * @param any an object.
     * @throws IOException if an IO error occurred
     */
    public void print(Object any) throws IOException {
        out.write(String.valueOf(any));
    }
}
