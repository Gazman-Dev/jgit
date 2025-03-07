/*
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.ProgressMonitor;

import java.io.OutputStream;
import java.util.Map;

/**
 * Lists known refs from the remote and sends objects to the remote.
 * <p>
 * A push connection typically connects to the <code>git-receive-pack</code>
 * service running where the remote repository is stored. This provides a
 * one-way object transfer service to copy objects from the local repository
 * into the remote repository, as well as a way to modify the refs stored by the
 * remote repository.
 * <p>
 * Instances of a PushConnection must be created by a
 * {@link Transport} that implements a specific
 * object transfer protocol that both sides of the connection understand.
 * <p>
 * PushConnection instances are not thread safe and may be accessed by only one
 * thread at a time.
 *
 * @see Transport
 */
public interface PushConnection extends Connection {

    /**
     * Pushes to the remote repository basing on provided specification. This
     * possibly result in update/creation/deletion of refs on remote repository
     * and sending objects that remote repository need to have a consistent
     * objects graph from new refs.
     * <p>
     * Only one call per connection is allowed. Subsequent calls will result in
     * {@link TransportException}.
     * <p>
     * Implementation may use local repository to send a minimum set of objects
     * needed by remote repository in efficient way.
     * {@link Transport#isPushThin()} should be
     * honored if applicable. refUpdates should be filled with information about
     * status of each update.
     *
     * @param monitor    progress monitor to update the end-user about the amount of
     *                   work completed, or to indicate cancellation. Implementors
     *                   should poll the monitor at regular intervals to look for
     *                   cancellation requests from the user.
     * @param refUpdates map of remote refnames to remote refs update
     *                   specifications/statuses. Can't be empty. This indicate what
     *                   refs caller want to update on remote side. Only refs updates
     *                   with
     *                   {@link RemoteRefUpdate.Status#NOT_ATTEMPTED}
     *                   should passed. Implementation must ensure that and appropriate
     *                   status with optional message should be set during call. No
     *                   refUpdate with
     *                   {@link RemoteRefUpdate.Status#AWAITING_REPORT}
     *                   or
     *                   {@link RemoteRefUpdate.Status#NOT_ATTEMPTED}
     *                   can be leaved by implementation after return from this call.
     * @throws TransportException objects could not be copied due to a network failure,
     *                            critical protocol error, or error on remote side, or
     *                            connection was already used for push - new connection must be
     *                            created. Non-critical errors concerning only isolated refs
     *                            should be placed in refUpdates.
     */
    void push(final ProgressMonitor monitor,
              final Map<String, RemoteRefUpdate> refUpdates)
            throws TransportException;

    /**
     * Pushes to the remote repository basing on provided specification. This
     * possibly result in update/creation/deletion of refs on remote repository
     * and sending objects that remote repository need to have a consistent
     * objects graph from new refs.
     * <p>
     * Only one call per connection is allowed. Subsequent calls will result in
     * {@link TransportException}.
     * <p>
     * Implementation may use local repository to send a minimum set of objects
     * needed by remote repository in efficient way.
     * {@link Transport#isPushThin()} should be
     * honored if applicable. refUpdates should be filled with information about
     * status of each update.
     *
     * @param monitor    progress monitor to update the end-user about the amount of
     *                   work completed, or to indicate cancellation. Implementors
     *                   should poll the monitor at regular intervals to look for
     *                   cancellation requests from the user.
     * @param refUpdates map of remote refnames to remote refs update
     *                   specifications/statuses. Can't be empty. This indicate what
     *                   refs caller want to update on remote side. Only refs updates
     *                   with
     *                   {@link RemoteRefUpdate.Status#NOT_ATTEMPTED}
     *                   should passed. Implementation must ensure that and appropriate
     *                   status with optional message should be set during call. No
     *                   refUpdate with
     *                   {@link RemoteRefUpdate.Status#AWAITING_REPORT}
     *                   or
     *                   {@link RemoteRefUpdate.Status#NOT_ATTEMPTED}
     *                   can be leaved by implementation after return from this call.
     * @param out        output stream to write sideband messages to
     * @throws TransportException objects could not be copied due to a network failure,
     *                            critical protocol error, or error on remote side, or
     *                            connection was already used for push - new connection must be
     *                            created. Non-critical errors concerning only isolated refs
     *                            should be placed in refUpdates.
     * @since 3.0
     */
    void push(final ProgressMonitor monitor,
              final Map<String, RemoteRefUpdate> refUpdates, OutputStream out)
            throws TransportException;

}
