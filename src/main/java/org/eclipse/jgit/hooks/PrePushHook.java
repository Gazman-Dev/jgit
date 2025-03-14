/*
 * Copyright (C) 2015, 2022 Obeo and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.hooks;

import org.eclipse.jgit.api.errors.AbortedByHookException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RemoteRefUpdate;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;

/**
 * The <code>pre-push</code> hook implementation. The pre-push hook runs during
 * git push, after the remote refs have been updated but before any objects have
 * been transferred.
 *
 * @since 4.2
 */
public class PrePushHook extends GitHook<String> {

    /**
     * Constant indicating the name of the pre-push hook.
     */
    public static final String NAME = "pre-push"; //$NON-NLS-1$

    private String remoteName;

    private String remoteLocation;

    private String refs;

    private boolean dryRun;

    /**
     * Constructor for PrePushHook
     * <p>
     * This constructor will use the default error stream.
     * </p>
     *
     * @param repo         The repository
     * @param outputStream The output stream the hook must use. {@code null} is allowed,
     *                     in which case the hook will use {@code System.out}.
     */
    protected PrePushHook(Repository repo, PrintStream outputStream) {
        super(repo, outputStream);
    }

    /**
     * Constructor for PrePushHook
     *
     * @param repo         The repository
     * @param outputStream The output stream the hook must use. {@code null} is allowed,
     *                     in which case the hook will use {@code System.out}.
     * @param errorStream  The error stream the hook must use. {@code null} is allowed,
     *                     in which case the hook will use {@code System.err}.
     * @since 5.6
     */
    protected PrePushHook(Repository repo, PrintStream outputStream,
                          PrintStream errorStream) {
        super(repo, outputStream, errorStream);
    }

    @Override
    protected String getStdinArgs() {
        return refs;
    }

    @Override
    public String call() throws IOException, AbortedByHookException {
        if (canRun()) {
            doRun();
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * @return {@code true}
     */
    private boolean canRun() {
        return true;
    }

    @Override
    public String getHookName() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This hook receives two parameters, which is the name and the location of
     * the remote repository.
     */
    @Override
    protected String[] getParameters() {
        if (remoteName == null) {
            remoteName = remoteLocation;
        }
        return new String[]{remoteName, remoteLocation};
    }

    /**
     * Set remote name
     *
     * @param name remote name
     */
    public void setRemoteName(String name) {
        remoteName = name;
    }

    /**
     * Get remote name
     *
     * @return remote name or null
     * @since 4.11
     */
    protected String getRemoteName() {
        return remoteName;
    }

    /**
     * Set remote location
     *
     * @param location a remote location
     */
    public void setRemoteLocation(String location) {
        remoteLocation = location;
    }

    /**
     * Sets whether the push is a dry run.
     *
     * @param dryRun {@code true} if the push is a dry run, {@code false} otherwise
     * @since 6.2
     */
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Tells whether the push is a dry run.
     *
     * @return {@code true} if the push is a dry run, {@code false} otherwise
     * @since 6.2
     */
    protected boolean isDryRun() {
        return dryRun;
    }

    /**
     * Set Refs
     *
     * @param toRefs a collection of {@code RemoteRefUpdate}s
     */
    public void setRefs(Collection<RemoteRefUpdate> toRefs) {
        StringBuilder b = new StringBuilder();
        for (RemoteRefUpdate u : toRefs) {
            b.append(u.getSrcRef());
            b.append(" "); //$NON-NLS-1$
            b.append(u.getNewObjectId().getName());
            b.append(" "); //$NON-NLS-1$
            b.append(u.getRemoteName());
            b.append(" "); //$NON-NLS-1$
            ObjectId ooid = u.getExpectedOldObjectId();
            b.append((ooid == null) ? ObjectId.zeroId().getName() : ooid
                    .getName());
            b.append("\n"); //$NON-NLS-1$
        }
        refs = b.toString();
    }
}
