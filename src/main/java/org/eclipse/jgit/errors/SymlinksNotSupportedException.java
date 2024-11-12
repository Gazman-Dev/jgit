/*
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2007, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.errors;

import java.io.IOException;

/**
 * An exception thrown when a symlink entry is found and cannot be
 * handled.
 */
public class SymlinksNotSupportedException extends IOException {
    private static final long serialVersionUID = 1L;

    /**
     * Construct a SymlinksNotSupportedException for the specified link
     *
     * @param s name of link in tree or workdir
     */
    public SymlinksNotSupportedException(String s) {
        super(s);
    }
}
