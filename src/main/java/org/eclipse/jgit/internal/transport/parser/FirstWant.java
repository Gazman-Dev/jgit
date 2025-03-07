/*
 * Copyright (C) 2018, Google LLC. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.transport.parser;

import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_AGENT;
import static org.eclipse.jgit.transport.GitProtocolConstants.OPTION_SESSION_ID;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.internal.JGitText;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * In the pack negotiation phase (protocol v0/v1), the client sends a list of
 * wants. The first "want" line is special, as it (can) have a list of
 * capabilities appended.
 * <p>
 * E.g. "want oid cap1 cap2 cap3"
 * <p>
 * Do not confuse this line with the first one in the reference advertisement,
 * which is sent by the server, looks like
 * "b8f7c471373b8583ced0025cfad8c9916c484b76 HEAD\0 cap1 cap2 cap3" and is
 * parsed by the BasePackConnection.readAdvertisedRefs method.
 * <p>
 * This class parses the input want line and holds the results: the actual want
 * line and the capabilities.
 */
public class FirstWant {
    private final String line;

    private final Set<String> capabilities;

    @Nullable
    private final String agent;

    @Nullable
    private final String clientSID;

    private static final String AGENT_PREFIX = OPTION_AGENT + '=';

    private static final String SESSION_ID_PREFIX = OPTION_SESSION_ID + '=';

    /**
     * Parse the first want line in the protocol v0/v1 pack negotiation.
     *
     * @param line line from the client.
     * @return an instance of FirstWant
     * @throws PackProtocolException if the line doesn't follow the protocol format.
     */
    public static FirstWant fromLine(String line) throws PackProtocolException {
        String wantLine;
        Set<String> capabilities;
        String agent = null;
        String clientSID = null;

        if (line.length() > 45) {
            String opt = line.substring(45);
            if (!opt.startsWith(" ")) { //$NON-NLS-1$
                throw new PackProtocolException(JGitText.get().wantNoSpaceWithCapabilities);
            }
            opt = opt.substring(1);

            HashSet<String> opts = new HashSet<>();
            for (String clientCapability : opt.split(" ")) { //$NON-NLS-1$
                if (clientCapability.startsWith(AGENT_PREFIX)) {
                    agent = clientCapability.substring(AGENT_PREFIX.length());
                } else if (clientCapability.startsWith(SESSION_ID_PREFIX)) {
                    clientSID = clientCapability
                            .substring(SESSION_ID_PREFIX.length());
                } else {
                    opts.add(clientCapability);
                }
            }
            wantLine = line.substring(0, 45);
            capabilities = Collections.unmodifiableSet(opts);
        } else {
            wantLine = line;
            capabilities = Collections.emptySet();
        }

        return new FirstWant(wantLine, capabilities, agent, clientSID);
    }

    private FirstWant(String line, Set<String> capabilities,
                      @Nullable String agent, @Nullable String clientSID) {
        this.line = line;
        this.capabilities = capabilities;
        this.agent = agent;
        this.clientSID = clientSID;
    }

    /**
     * Get line
     *
     * @return non-capabilities part of the line.
     */
    public String getLine() {
        return line;
    }

    /**
     * Get capabilities
     *
     * @return capabilities parsed from the line as an immutable set (excluding
     * agent and session-id).
     */
    public Set<String> getCapabilities() {
        return capabilities;
    }

    /**
     * Get agent
     *
     * @return client user agent parsed from the line.
     */
    @Nullable
    public String getAgent() {
        return agent;
    }

    /**
     * Get client session-id
     *
     * @return client session-id parsed from the line.
     */
    @Nullable
    public String getClientSID() {
        return clientSID;
    }
}
