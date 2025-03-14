/*
 * Copyright (C) 2008, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.treewalk;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;

/**
 * Specialized TreeWalk to detect directory-file (D/F) name conflicts.
 * <p>
 * Due to the way a Git tree is organized the standard
 * {@link TreeWalk} won't easily find a D/F conflict
 * when merging two or more trees together. In the standard TreeWalk the file
 * will be returned first, and then much later the directory will be returned.
 * This makes it impossible for the application to efficiently detect and handle
 * the conflict.
 * <p>
 * Using this walk implementation causes the directory to report earlier than
 * usual, at the same time as the non-directory entry. This permits the
 * application to handle the D/F conflict in a single step. The directory is
 * returned only once, so it does not get returned later in the iteration.
 * <p>
 * When a D/F conflict is detected
 * {@link TreeWalk#isSubtree()} will return true and
 * {@link TreeWalk#enterSubtree()} will recurse into
 * the subtree, no matter which iterator originally supplied the subtree.
 * <p>
 * Because conflicted directories report early, using this walk implementation
 * to populate a {@link org.eclipse.jgit.dircache.DirCacheBuilder} may cause the
 * automatic resorting to run and fix the entry ordering.
 * <p>
 * This walk implementation requires more CPU to implement a look-ahead and a
 * look-behind to merge a D/F pair together, or to skip a previously reported
 * directory. In typical Git repositories the look-ahead cost is 0 and the
 * look-behind doesn't trigger, as users tend not to create trees which contain
 * both "foo" as a directory and "foo.c" as a file.
 * <p>
 * In the worst-case however several thousand look-ahead steps per walk step may
 * be necessary, making the overhead quite significant. Since this worst-case
 * should never happen this walk implementation has made the time/space tradeoff
 * in favor of more-time/less-space, as that better suits the typical case.
 */
public class NameConflictTreeWalk extends TreeWalk {
    private static final int TREE_MODE = FileMode.TREE.getBits();

    /**
     * True if all {@link #trees} point to entries with equal names.
     * <p>
     * If at least one tree iterator point to a different name or
     * reached end of the tree, the value is false.
     * Note: if all iterators reached end of trees, the value is true.
     */
    private boolean allTreesNamesMatchFastMinRef;

    private AbstractTreeIterator dfConflict;

    /**
     * Create a new tree walker for a given repository.
     *
     * @param repo the repository the walker will obtain data from.
     */
    public NameConflictTreeWalk(Repository repo) {
        super(repo);
    }

    /**
     * Create a new tree walker for a given repository.
     *
     * @param repo the repository the walker will obtain data from.
     * @param or   the reader the walker will obtain tree data from.
     * @since 4.3
     */
    public NameConflictTreeWalk(@Nullable Repository repo, ObjectReader or) {
        super(repo, or);
    }

    /**
     * Create a new tree walker for a given repository.
     *
     * @param or the reader the walker will obtain tree data from.
     */
    public NameConflictTreeWalk(ObjectReader or) {
        super(or);
    }

    @Override
    AbstractTreeIterator min() throws CorruptObjectException {
        for (; ; ) {
            final AbstractTreeIterator minRef = fastMin();
            if (allTreesNamesMatchFastMinRef)
                return minRef;

            if (isTree(minRef)) {
                if (skipEntry(minRef)) {
                    for (AbstractTreeIterator t : trees) {
                        if (t.matches == minRef) {
                            t.next(1);
                            t.matches = null;
                        }
                    }
                    continue;
                }
                return minRef;
            }

            return combineDF(minRef);
        }
    }

    private AbstractTreeIterator fastMin() {
        int i = getFirstNonEofTreeIndex();
        if (i == -1) {
            // All trees reached the end.
            allTreesNamesMatchFastMinRef = true;
            return trees[trees.length - 1];
        }
        AbstractTreeIterator minRef = trees[i];
        // if i > 0 then we already know that only some trees reached the end
        // (but not all), so it is impossible that ALL trees points to the
        // minRef entry.
        // Only if i == 0 it is still possible that all trees points to the same
        // minRef entry.
        allTreesNamesMatchFastMinRef = i == 0;
        boolean hasConflict = false;
        minRef.matches = minRef;
        while (++i < trees.length) {
            final AbstractTreeIterator t = trees[i];
            if (t.eof()) {
                allTreesNamesMatchFastMinRef = false;
                continue;
            }

            final int cmp = t.pathCompare(minRef);
            if (cmp < 0) {
                if (allTreesNamesMatchFastMinRef && isTree(minRef) && !isTree(t)
                        && nameEqual(minRef, t)) {
                    // We used to be at a tree, but now we are at a file
                    // with the same name. Allow the file to match the
                    // tree anyway.
                    //
                    t.matches = minRef;
                    hasConflict = true;
                } else {
                    allTreesNamesMatchFastMinRef = false;
                    t.matches = t;
                    minRef = t;
                }
            } else if (cmp == 0) {
                // Exact name/mode match is best.
                //
                t.matches = minRef;
            } else if (allTreesNamesMatchFastMinRef && isTree(t)
                    && !isTree(minRef) && !isGitlink(minRef)
                    && nameEqual(t, minRef)) {
                // The minimum is a file (non-tree) but the next entry
                // of this iterator is a tree whose name matches our file.
                // This is a classic D/F conflict and commonly occurs like
                // this, with no gaps in between the file and directory.
                //
                // Use the tree as the minimum instead (see combineDF).
                //

                for (int k = 0; k < i; k++) {
                    final AbstractTreeIterator p = trees[k];
                    if (p.matches == minRef)
                        p.matches = t;
                }
                t.matches = t;
                minRef = t;
                hasConflict = true;
            } else
                allTreesNamesMatchFastMinRef = false;
        }

        if (hasConflict && allTreesNamesMatchFastMinRef && dfConflict == null)
            dfConflict = minRef;
        return minRef;
    }

    private int getFirstNonEofTreeIndex() {
        for (int i = 0; i < trees.length; i++) {
            if (!trees[i].eof()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean nameEqual(final AbstractTreeIterator a,
                                     final AbstractTreeIterator b) {
        return a.pathCompare(b, TREE_MODE) == 0;
    }

    private boolean isGitlink(AbstractTreeIterator p) {
        return FileMode.GITLINK.equals(p.mode);
    }

    private static boolean isTree(AbstractTreeIterator p) {
        return FileMode.TREE.equals(p.mode);
    }

    private boolean skipEntry(AbstractTreeIterator minRef)
            throws CorruptObjectException {
        // A tree D/F may have been handled earlier. We need to
        // not report this path if it has already been reported.
        //
        for (AbstractTreeIterator t : trees) {
            if (t.matches == minRef || t.first())
                continue;

            int stepsBack = 0;
            for (; ; ) {
                stepsBack++;
                t.back(1);

                final int cmp = t.pathCompare(minRef, 0);
                if (cmp == 0) {
                    // We have already seen this "$path" before. Skip it.
                    //
                    t.next(stepsBack);
                    return true;
                } else if (cmp < 0 || t.first()) {
                    // We cannot find "$path" in t; it will never appear.
                    //
                    t.next(stepsBack);
                    break;
                }
            }
        }

        // We have never seen the current path before.
        //
        return false;
    }

    private AbstractTreeIterator combineDF(AbstractTreeIterator minRef)
            throws CorruptObjectException {
        // Look for a possible D/F conflict forward in the tree(s)
        // as there may be a "$path/" which matches "$path". Make
        // such entries match this entry.
        //
        AbstractTreeIterator treeMatch = null;
        for (AbstractTreeIterator t : trees) {
            if (t.matches == minRef || t.eof())
                continue;

            for (; ; ) {
                final int cmp = t.pathCompare(minRef, TREE_MODE);
                if (cmp < 0) {
                    // The "$path/" may still appear later.
                    //
                    t.matchShift++;
                    t.next(1);
                    if (t.eof()) {
                        t.back(t.matchShift);
                        t.matchShift = 0;
                        break;
                    }
                } else if (cmp == 0) {
                    // We have a conflict match here.
                    //
                    t.matches = minRef;
                    treeMatch = t;
                    break;
                } else {
                    // A conflict match is not possible.
                    //
                    if (t.matchShift != 0) {
                        t.back(t.matchShift);
                        t.matchShift = 0;
                    }
                    break;
                }
            }
        }

        // When the combineDF is called, the t.matches field stores other
        // entry (i.e. tree iterator) with an equal path. However, the
        // previous loop moves each iterator independently. As a result,
        // iterators which have had equals path at the start of the
        // method can have different paths at this point.
        // Reevaluate existing matches.
        // The NameConflictTreeWalkTest.testDF_specialFileNames test
        // cover this situation.
        for (AbstractTreeIterator t : trees) {
            // The previous loop doesn't touch tree iterator if it matches
            // minRef. Skip it here
            if (t.eof() || t.matches == null || t.matches == minRef)
                continue;
            // The t.pathCompare takes into account the entry type (file
            // or directory) and returns non-zero value if names match
            // but entry type don't match.
            // We want to keep such matches (file/directory conflict),
            // so reset matches only if names are not equal.
            if (!nameEqual(t, t.matches))
                t.matches = null;
        }

        if (treeMatch != null) {
            // If we do have a conflict use one of the directory
            // matching iterators instead of the file iterator.
            // This way isSubtree is true and isRecursive works.
            //
            for (AbstractTreeIterator t : trees)
                if (t.matches == minRef)
                    t.matches = treeMatch;

            if (dfConflict == null && !isGitlink(minRef)) {
                dfConflict = treeMatch;
            }

            return treeMatch;
        }

        return minRef;
    }

    @Override
    void popEntriesEqual() throws CorruptObjectException {
        final AbstractTreeIterator ch = currentHead;
        for (AbstractTreeIterator t : trees) {
            if (t.matches == ch) {
                if (t.matchShift == 0)
                    t.next(1);
                else {
                    t.back(t.matchShift);
                    t.matchShift = 0;
                }
                t.matches = null;
            }
        }

        if (ch == dfConflict)
            dfConflict = null;
    }

    @Override
    void skipEntriesEqual() throws CorruptObjectException {
        final AbstractTreeIterator ch = currentHead;
        for (AbstractTreeIterator t : trees) {
            if (t.matches == ch) {
                if (t.matchShift == 0)
                    t.skip();
                else {
                    t.back(t.matchShift);
                    t.matchShift = 0;
                }
                t.matches = null;
            }
        }

        if (ch == dfConflict)
            dfConflict = null;
    }

    @Override
    void stopWalk() throws IOException {
        if (!needsStopWalk()) {
            return;
        }

        // Name conflicts make aborting early difficult. Multiple paths may
        // exist between the file and directory versions of a name. To ensure
        // the directory version is skipped over (as it was previously visited
        // during the file version step) requires popping up the stack and
        // finishing out each subtree that the walker dove into. Siblings in
        // parents do not need to be recursed into, bounding the cost.
        for (; ; ) {
            AbstractTreeIterator t = min();
            if (t.eof()) {
                if (depth > 0) {
                    exitSubtree();
                    popEntriesEqual();
                    continue;
                }
                return;
            }
            currentHead = t;
            skipEntriesEqual();
        }
    }

    private boolean needsStopWalk() {
        for (AbstractTreeIterator t : trees) {
            if (t.needsStopWalk()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if the current entry is covered by a directory/file conflict.
     * <p>
     * This means that for some prefix of the current entry's path, this walk
     * has detected a directory/file conflict. Also true if the current entry
     * itself is a directory/file conflict.
     * <p>
     * Example: If this TreeWalk points to foo/bar/a.txt and this method returns
     * true then you know that either for path foo or for path foo/bar files and
     * folders were detected.
     *
     * @return <code>true</code> if the current entry is covered by a
     * directory/file conflict, <code>false</code> otherwise
     */
    public boolean isDirectoryFileConflict() {
        return dfConflict != null;
    }
}
