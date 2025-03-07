/*
 * Copyright (C) 2018, 2022 Google LLC. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.transport;

import static java.util.Objects.requireNonNull;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.ObjectId;

import java.util.List;
import java.util.Set;

/**
 * Common fields between v0/v1/v2 fetch requests.
 */
abstract class FetchRequest {

    final Set<ObjectId> wantIds;

    final int depth;

    final Set<ObjectId> clientShallowCommits;

    final FilterSpec filterSpec;

    final Set<String> clientCapabilities;

    final int deepenSince;

    final List<String> deepenNots;

    @Nullable
    final String agent;

    @Nullable
    final String clientSID;

    /**
     * Initialize the common fields of a fetch request.
     *
     * @param wantIds              list of want ids
     * @param depth                how deep to go in the tree
     * @param clientShallowCommits commits the client has without history
     * @param filterSpec           the filter spec
     * @param clientCapabilities   capabilities sent in the request
     * @param deepenNots           Requests that the shallow clone/fetch should be cut at these
     *                             specific revisions instead of a depth.
     * @param deepenSince          Requests that the shallow clone/fetch should be cut at a
     *                             specific time, instead of depth
     * @param agent                agent as reported by the client in the request body
     * @param clientSID            agent as reported by the client in the request body
     */
    FetchRequest(@NonNull Set<ObjectId> wantIds, int depth,
                 @NonNull Set<ObjectId> clientShallowCommits,
                 @NonNull FilterSpec filterSpec,
                 @NonNull Set<String> clientCapabilities, int deepenSince,
                 @NonNull List<String> deepenNots, @Nullable String agent,
                 @Nullable String clientSID) {
        this.wantIds = requireNonNull(wantIds);
        this.depth = depth;
        this.clientShallowCommits = requireNonNull(clientShallowCommits);
        this.filterSpec = requireNonNull(filterSpec);
        this.clientCapabilities = requireNonNull(clientCapabilities);
        this.deepenSince = deepenSince;
        this.deepenNots = requireNonNull(deepenNots);
        this.agent = agent;
        this.clientSID = clientSID;
    }

    /**
     * Get object ids in the "want" lines
     *
     * @return object ids in the "want" (and "want-ref") lines of the request
     */
    @NonNull
    Set<ObjectId> getWantIds() {
        return wantIds;
    }

    /**
     * Get the depth set in a "deepen" line
     *
     * @return the depth set in a "deepen" line. 0 by default.
     */
    int getDepth() {
        return depth;
    }

    /**
     * Shallow commits the client already has.
     * <p>
     * These are sent by the client in "shallow" request lines.
     *
     * @return set of commits the client has declared as shallow.
     */
    @NonNull
    Set<ObjectId> getClientShallowCommits() {
        return clientShallowCommits;
    }

    /**
     * Get the filter spec given in a "filter" line
     *
     * @return the filter spec given in a "filter" line
     */
    @NonNull
    FilterSpec getFilterSpec() {
        return filterSpec;
    }

    /**
     * Capabilities that the client wants enabled from the server.
     * <p>
     * Capabilities are options that tune the expected response from the server,
     * like "thin-pack", "no-progress" or "ofs-delta". This list should be a
     * subset of the capabilities announced by the server in its first response.
     * <p>
     * These options are listed and well-defined in the git protocol
     * specification.
     * <p>
     * The agent capability is not included in this set. It can be retrieved via
     * {@link #getAgent()}.
     *
     * @return capabilities sent by the client (excluding the "agent"
     * capability)
     */
    @NonNull
    Set<String> getClientCapabilities() {
        return clientCapabilities;
    }

    /**
     * The value in a "deepen-since" line in the request, indicating the
     * timestamp where to stop fetching/cloning.
     *
     * @return timestamp in seconds since the epoch, where to stop the shallow
     * fetch/clone. Defaults to 0 if not set in the request.
     */
    int getDeepenSince() {
        return deepenSince;
    }

    /**
     * Get refs received in "deepen-not" lines
     *
     * @return refs received in "deepen-not" lines.
     */
    @NonNull
    List<String> getDeepenNots() {
        return deepenNots;
    }

    /**
     * Get string identifying the agent
     *
     * @return string identifying the agent (as sent in the request body by the
     * client)
     */
    @Nullable
    String getAgent() {
        return agent;
    }

    /**
     * Get string identifying the client session ID
     *
     * @return string identifying the client session ID (as sent in the request
     * body by the client)
     */
    @Nullable
    String getClientSID() {
        return clientSID;
    }
}
