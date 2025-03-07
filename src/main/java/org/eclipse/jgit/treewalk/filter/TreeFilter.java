/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.treewalk.filter;

import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Selects interesting tree entries during walking.
 * <p>
 * This is an abstract interface. Applications may implement a subclass, or use
 * one of the predefined implementations already available within this package.
 * <p>
 * Unless specifically noted otherwise a TreeFilter implementation is not thread
 * safe and may not be shared by different TreeWalk instances at the same time.
 * This restriction allows TreeFilter implementations to cache state within
 * their instances during {@link #include(TreeWalk)} if it is beneficial to
 * their implementation. Deep clones created by {@link #clone()} may be used to
 * construct a thread-safe copy of an existing filter.
 *
 * <p>
 * <b>Path filters:</b>
 * <ul>
 * <li>Matching pathname:
 * {@link PathFilter}</li>
 * </ul>
 *
 * <p>
 * <b>Difference filters:</b>
 * <ul>
 * <li>Only select differences: {@link #ANY_DIFF}.</li>
 * </ul>
 *
 * <p>
 * <b>Boolean modifiers:</b>
 * <ul>
 * <li>AND: {@link AndTreeFilter}</li>
 * <li>OR: {@link OrTreeFilter}</li>
 * <li>NOT: {@link NotTreeFilter}</li>
 * </ul>
 */
public abstract class TreeFilter {
    /**
     * Selects all tree entries.
     */
    public static final TreeFilter ALL = new AllFilter();

    private static final class AllFilter extends TreeFilter {
        @Override
        public boolean include(TreeWalk walker) {
            return true;
        }

        @Override
        public boolean shouldBeRecursive() {
            return false;
        }

        @Override
        public TreeFilter clone() {
            return this;
        }

        @Override
        public String toString() {
            return "ALL"; //$NON-NLS-1$
        }
    }

    /**
     * Selects only tree entries which differ between at least 2 trees.
     * <p>
     * This filter also prevents a TreeWalk from recursing into a subtree if all
     * parent trees have the identical subtree at the same path. This
     * dramatically improves walk performance as only the changed subtrees are
     * entered into.
     * <p>
     * If this filter is applied to a walker with only one tree it behaves like
     * {@link #ALL}, or as though the walker was matching a virtual empty tree
     * against the single tree it was actually given. Applications may wish to
     * treat such a difference as "all names added".
     * <p>
     * When comparing {@link WorkingTreeIterator} and {@link DirCacheIterator}
     * applications should use {@link IndexDiffFilter}.
     */
    public static final TreeFilter ANY_DIFF = new AnyDiffFilter();

    private static final class AnyDiffFilter extends TreeFilter {
        private static final int baseTree = 0;

        @Override
        public boolean include(TreeWalk walker) {
            final int n = walker.getTreeCount();
            if (n == 1) // Assume they meant difference to empty tree.
                return true;

            final int m = walker.getRawMode(baseTree);
            for (int i = 1; i < n; i++)
                if (walker.getRawMode(i) != m || !walker.idEqual(i, baseTree))
                    return true;
            return false;
        }

        @Override
        public boolean shouldBeRecursive() {
            return false;
        }

        @Override
        public TreeFilter clone() {
            return this;
        }

        @Override
        public String toString() {
            return "ANY_DIFF"; //$NON-NLS-1$
        }
    }

    /**
     * Create a new filter that does the opposite of this filter.
     *
     * @return a new filter that includes tree entries this filter rejects.
     */
    public TreeFilter negate() {
        return NotTreeFilter.create(this);
    }

    /**
     * Determine if the current entry is interesting to report.
     * <p>
     * This method is consulted for subtree entries even if
     * {@link TreeWalk#isRecursive()} is enabled. The
     * consultation allows the filter to bypass subtree recursion on a
     * case-by-case basis, even when recursion is enabled at the application
     * level.
     *
     * @param walker the walker the filter needs to examine.
     * @return true if the current entry should be seen by the application;
     * false to hide the entry.
     * @throws MissingObjectException       an object the filter needs to consult to determine its answer
     *                                      does not exist in the Git repository the walker is operating
     *                                      on. Filtering this current walker entry is impossible without
     *                                      the object.
     * @throws IncorrectObjectTypeException an object the filter needed to consult was not of the
     *                                      expected object type. This usually indicates a corrupt
     *                                      repository, as an object link is referencing the wrong type.
     * @throws IOException                  a loose object or pack file could not be read to obtain data
     *                                      necessary for the filter to make its decision.
     */
    public abstract boolean include(TreeWalk walker)
            throws MissingObjectException, IncorrectObjectTypeException,
            IOException;

    /**
     * Determine if the current entry is a parent, a match, or no match.
     * <p>
     * This method extends the result returned by {@link #include(TreeWalk)}
     * with a third option (-1), splitting the value true. This gives the
     * application a possibility to distinguish between an exact match and the
     * case when a subtree to the current entry might be a match.
     *
     * @param walker the walker the filter needs to examine.
     * @return -1 if the current entry is a parent of the filter but no exact
     * match has been made; 0 if the current entry should be seen by the
     * application; 1 if it should be hidden.
     * @throws MissingObjectException       as thrown by {@link #include(TreeWalk)}
     * @throws IncorrectObjectTypeException as thrown by {@link #include(TreeWalk)}
     * @throws IOException                  as thrown by {@link #include(TreeWalk)}
     * @since 4.7
     */
    public int matchFilter(TreeWalk walker) throws MissingObjectException,
            IncorrectObjectTypeException, IOException {
        return include(walker) ? 0 : 1;
    }

    /**
     * Does this tree filter require a recursive walk to match everything?
     * <p>
     * If this tree filter is matching on full entry path names and its pattern
     * is looking for a '/' then the filter would require a recursive TreeWalk
     * to accurately make its decisions. The walker is not required to enable
     * recursive behavior for any particular filter, this is only a hint.
     *
     * @return true if the filter would like to have the walker recurse into
     * subtrees to make sure it matches everything correctly; false if
     * the filter does not require entering subtrees.
     */
    public abstract boolean shouldBeRecursive();

    /**
     * If this filter checks that a specific set of paths have all been
     * modified, returns that set of paths to be checked against a changed path
     * filter. Otherwise, returns empty.
     *
     * @return a set of paths, or empty
     * @since 6.7
     */
    public Optional<Set<byte[]>> getPathsBestEffort() {
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Clone this tree filter, including its parameters.
     * <p>
     * This is a deep clone. If this filter embeds objects or other filters it
     * must also clone those, to ensure the instances do not share mutable data.
     */
    @Override
    public abstract TreeFilter clone();

    @Override
    public String toString() {
        String n = getClass().getName();
        int lastDot = n.lastIndexOf('.');
        if (lastDot >= 0) {
            n = n.substring(lastDot + 1);
        }
        return n.replace('$', '.');
    }
}
