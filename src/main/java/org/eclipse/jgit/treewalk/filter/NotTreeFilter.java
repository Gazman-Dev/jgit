/*
 * Copyright (C) 2008, Google Inc.
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.treewalk.filter;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;

/**
 * Includes an entry only if the subfilter does not include the entry.
 */
public class NotTreeFilter extends TreeFilter {
    /**
     * Create a filter that negates the result of another filter.
     *
     * @param a filter to negate.
     * @return a filter that does the reverse of <code>a</code>.
     */
    public static TreeFilter create(TreeFilter a) {
        return new NotTreeFilter(a);
    }

    private final TreeFilter a;

    private NotTreeFilter(TreeFilter one) {
        a = one;
    }

    @Override
    public TreeFilter negate() {
        return a;
    }

    @Override
    public boolean include(TreeWalk walker)
            throws MissingObjectException, IncorrectObjectTypeException,
            IOException {
        return matchFilter(walker) == 0;
    }

    @Override
    public int matchFilter(TreeWalk walker)
            throws MissingObjectException, IncorrectObjectTypeException,
            IOException {
        final int r = a.matchFilter(walker);
        // switch 0 and 1, keep -1 as that defines a subpath that must be
        // traversed before a final verdict can be made.
        if (r == 0) {
            return 1;
        }
        if (r == 1) {
            return 0;
        }
        return -1;
    }

    @Override
    public boolean shouldBeRecursive() {
        return a.shouldBeRecursive();
    }

    @Override
    public TreeFilter clone() {
        final TreeFilter n = a.clone();
        return n == a ? this : new NotTreeFilter(n);
    }

    @Override
    public String toString() {
        return "NOT " + a.toString(); //$NON-NLS-1$
    }
}
