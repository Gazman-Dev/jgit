/*
 * Copyright (C) 2008, 2010 Google Inc.
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, 2020 Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.QuotedString;
import org.eclipse.jgit.util.SystemReader;
import org.eclipse.jgit.util.io.MessageWriter;
import org.eclipse.jgit.util.io.StreamCopyThread;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Transport through an SSH tunnel.
 * <p>
 * The SSH transport requires the remote side to have Git installed, as the
 * transport logs into the remote system and executes a Git helper program on
 * the remote side to read (or write) the remote repository's files.
 * <p>
 * This transport does not support direct SCP style of copying files, as it
 * assumes there are Git specific smarts on the remote side to perform object
 * enumeration, save file modification and hook execution.
 */
public class TransportGitSsh extends SshTransport implements PackTransport {
    private static final String EXT = "ext"; //$NON-NLS-1$

    static final TransportProtocol PROTO_SSH = new TransportProtocol() {
        private final String[] schemeNames = {"ssh", "ssh+git", "git+ssh"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        private final Set<String> schemeSet = Collections
                .unmodifiableSet(new LinkedHashSet<>(Arrays
                        .asList(schemeNames)));

        @Override
        public String getName() {
            return JGitText.get().transportProtoSSH;
        }

        @Override
        public Set<String> getSchemes() {
            return schemeSet;
        }

        @Override
        public Set<URIishField> getRequiredFields() {
            return Collections.unmodifiableSet(EnumSet.of(URIishField.HOST,
                    URIishField.PATH));
        }

        @Override
        public Set<URIishField> getOptionalFields() {
            return Collections.unmodifiableSet(EnumSet.of(URIishField.USER,
                    URIishField.PASS, URIishField.PORT));
        }

        @Override
        public int getDefaultPort() {
            return 22;
        }

        @Override
        public boolean canHandle(URIish uri, Repository local, String remoteName) {
            if (uri.getScheme() == null) {
                // scp-style URI "host:path" does not have scheme.
                return uri.getHost() != null
                        && uri.getPath() != null
                        && uri.getHost().length() != 0
                        && uri.getPath().length() != 0;
            }
            return super.canHandle(uri, local, remoteName);
        }

        @Override
        public Transport open(URIish uri, Repository local, String remoteName)
                throws NotSupportedException {
            return new TransportGitSsh(local, uri);
        }

        @Override
        public Transport open(URIish uri) throws NotSupportedException, TransportException {
            return new TransportGitSsh(uri);
        }
    };

    TransportGitSsh(Repository local, URIish uri) {
        super(local, uri);
        initSshSessionFactory();
    }

    TransportGitSsh(URIish uri) {
        super(uri);
        initSshSessionFactory();
    }

    private void initSshSessionFactory() {
        if (useExtSession()) {
            setSshSessionFactory(new SshSessionFactory() {
                @Override
                public RemoteSession getSession(URIish uri2,
                                                CredentialsProvider credentialsProvider, FS fs, int tms)
                        throws TransportException {
                    return new ExtSession();
                }

                @Override
                public String getType() {
                    return EXT;
                }
            });
        }
    }

    @Override
    public FetchConnection openFetch() throws TransportException {
        return new SshFetchConnection();
    }

    @Override
    public FetchConnection openFetch(Collection<RefSpec> refSpecs,
                                     String... additionalPatterns)
            throws NotSupportedException, TransportException {
        return new SshFetchConnection(refSpecs, additionalPatterns);
    }

    @Override
    public PushConnection openPush() throws TransportException {
        return new SshPushConnection();
    }

    String commandFor(String exe) {
        String path = uri.getPath();
        if (uri.getScheme() != null && uri.getPath().startsWith("/~")) //$NON-NLS-1$
            path = uri.getPath().substring(1);

        final StringBuilder cmd = new StringBuilder();
        cmd.append(exe);
        cmd.append(' ');
        cmd.append(QuotedString.BOURNE.quote(path));
        return cmd.toString();
    }

    void checkExecFailure(int status, String exe, String why)
            throws TransportException {
        if (status == 127) {
            IOException cause = null;
            if (why != null && why.length() > 0)
                cause = new IOException(why);
            throw new TransportException(uri, MessageFormat.format(
                    JGitText.get().cannotExecute, commandFor(exe)), cause);
        }
    }

    NoRemoteRepositoryException cleanNotFound(NoRemoteRepositoryException nf,
                                              String why) {
        if (why == null || why.length() == 0)
            return nf;

        String path = uri.getPath();
        if (uri.getScheme() != null && uri.getPath().startsWith("/~")) //$NON-NLS-1$
            path = uri.getPath().substring(1);

        final StringBuilder pfx = new StringBuilder();
        pfx.append("fatal: "); //$NON-NLS-1$
        pfx.append(QuotedString.BOURNE.quote(path));
        pfx.append(": "); //$NON-NLS-1$
        if (why.startsWith(pfx.toString()))
            why = why.substring(pfx.length());

        return new NoRemoteRepositoryException(uri, why);
    }

    private static boolean useExtSession() {
        return SystemReader.getInstance().getenv("GIT_SSH") != null; //$NON-NLS-1$
    }

    private class ExtSession implements RemoteSession2 {

        @Override
        public Process exec(String command, int timeout)
                throws TransportException {
            return exec(command, null, timeout);
        }

        @Override
        public Process exec(String command, Map<String, String> environment,
                            int timeout) throws TransportException {
            String ssh = SystemReader.getInstance().getenv("GIT_SSH"); //$NON-NLS-1$
            boolean putty = ssh.toLowerCase(Locale.ROOT).contains("plink"); //$NON-NLS-1$

            List<String> args = new ArrayList<>();
            args.add(ssh);
            if (putty && !ssh.toLowerCase(Locale.ROOT)
                    .contains("tortoiseplink")) {//$NON-NLS-1$
                args.add("-batch"); //$NON-NLS-1$
            }
            if (0 < getURI().getPort()) {
                args.add(putty ? "-P" : "-p"); //$NON-NLS-1$ //$NON-NLS-2$
                args.add(String.valueOf(getURI().getPort()));
            }
            if (getURI().getUser() != null) {
                args.add(getURI().getUser() + "@" + getURI().getHost()); //$NON-NLS-1$
            } else {
                args.add(getURI().getHost());
            }
            args.add(command);

            ProcessBuilder pb = createProcess(args, environment);
            try {
                return pb.start();
            } catch (IOException err) {
                throw new TransportException(err.getMessage(), err);
            }
        }

        private ProcessBuilder createProcess(List<String> args,
                                             Map<String, String> environment) {
            ProcessBuilder pb = new ProcessBuilder();
            pb.command(args);
            if (environment != null) {
                pb.environment().putAll(environment);
            }
            File directory = local != null ? local.getDirectory() : null;
            if (directory != null) {
                pb.environment().put(Constants.GIT_DIR_KEY,
                        directory.getPath());
            }
            return pb;
        }

        @Override
        public void disconnect() {
            // Nothing to do
        }
    }

    class SshFetchConnection extends BasePackFetchConnection {
        private final Process process;

        private StreamCopyThread errorThread;

        SshFetchConnection() throws TransportException {
            this(Collections.emptyList());
        }

        SshFetchConnection(Collection<RefSpec> refSpecs,
                           String... additionalPatterns) throws TransportException {
            super(TransportGitSsh.this);
            try {
                RemoteSession session = getSession();
                TransferConfig.ProtocolVersion gitProtocol = protocol;
                if (gitProtocol == null) {
                    gitProtocol = TransferConfig.ProtocolVersion.V2;
                }
                if (session instanceof RemoteSession2
                        && TransferConfig.ProtocolVersion.V2
                        .equals(gitProtocol)) {
                    process = ((RemoteSession2) session).exec(
                            commandFor(getOptionUploadPack()), Collections
                                    .singletonMap(
                                            GitProtocolConstants.PROTOCOL_ENVIRONMENT_VARIABLE,
                                            GitProtocolConstants.VERSION_2_REQUEST),
                            getTimeout());
                } else {
                    process = session.exec(commandFor(getOptionUploadPack()),
                            getTimeout());
                }
                final MessageWriter msg = new MessageWriter();
                setMessageWriter(msg);

                final InputStream upErr = process.getErrorStream();
                errorThread = new StreamCopyThread(upErr, msg.getRawStream());
                errorThread.start();

                init(process.getInputStream(), process.getOutputStream());

            } catch (TransportException err) {
                close();
                throw err;
            } catch (Throwable err) {
                close();
                throw new TransportException(uri,
                        JGitText.get().remoteHungUpUnexpectedly, err);
            }

            try {
                if (!readAdvertisedRefs()) {
                    lsRefs(refSpecs, additionalPatterns);
                }
            } catch (NoRemoteRepositoryException notFound) {
                final String msgs = getMessages();
                checkExecFailure(process.exitValue(), getOptionUploadPack(),
                        msgs);
                throw cleanNotFound(notFound, msgs);
            }
        }

        @Override
        public void close() {
            endOut();

            if (process != null) {
                process.destroy();
            }
            if (errorThread != null) {
                try {
                    errorThread.halt();
                } catch (InterruptedException e) {
                    // Stop waiting and return anyway.
                } finally {
                    errorThread = null;
                }
            }

            super.close();
        }
    }

    class SshPushConnection extends BasePackPushConnection {
        private final Process process;

        private StreamCopyThread errorThread;

        SshPushConnection() throws TransportException {
            super(TransportGitSsh.this);
            try {
                process = getSession().exec(commandFor(getOptionReceivePack()),
                        getTimeout());
                final MessageWriter msg = new MessageWriter();
                setMessageWriter(msg);

                final InputStream rpErr = process.getErrorStream();
                errorThread = new StreamCopyThread(rpErr, msg.getRawStream());
                errorThread.start();

                init(process.getInputStream(), process.getOutputStream());

            } catch (TransportException err) {
                try {
                    close();
                } catch (Exception e) {
                    // ignore
                }
                throw err;
            } catch (Throwable err) {
                try {
                    close();
                } catch (Exception e) {
                    // ignore
                }
                throw new TransportException(uri,
                        JGitText.get().remoteHungUpUnexpectedly, err);
            }

            try {
                readAdvertisedRefs();
            } catch (NoRemoteRepositoryException notFound) {
                final String msgs = getMessages();
                checkExecFailure(process.exitValue(), getOptionReceivePack(),
                        msgs);
                throw cleanNotFound(notFound, msgs);
            }
        }

        @Override
        public void close() {
            endOut();

            if (process != null) {
                process.destroy();
            }
            if (errorThread != null) {
                try {
                    errorThread.halt();
                } catch (InterruptedException e) {
                    // Stop waiting and return anyway.
                } finally {
                    errorThread = null;
                }
            }

            super.close();
        }
    }
}
