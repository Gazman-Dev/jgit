/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import static org.eclipse.jgit.lib.Constants.INFO_ALTERNATES;
import static org.eclipse.jgit.lib.Constants.LOCK_SUFFIX;
import static org.eclipse.jgit.lib.Constants.OBJECTS;

import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Ref.Storage;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.SymbolicRef;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Transport over the non-Git aware SFTP (SSH based FTP) protocol.
 * <p>
 * The SFTP transport does not require any specialized Git support on the remote
 * (server side) repository. Object files are retrieved directly through secure
 * shell's FTP protocol, making it possible to copy objects from a remote
 * repository that is available over SSH, but whose remote host does not have
 * Git installed.
 * <p>
 * Unlike the HTTP variant (see
 * {@link TransportHttp}) we rely upon being able to
 * list files in directories, as the SFTP protocol supports this function. By
 * listing files through SFTP we can avoid needing to have current
 * <code>objects/info/packs</code> or <code>info/refs</code> files on the remote
 * repository and access the data directly, much as Git itself would.
 * <p>
 * Concurrent pushing over this transport is not supported. Multiple concurrent
 * push operations may cause confusion in the repository state.
 *
 * @see WalkFetchConnection
 */
public class TransportSftp extends SshTransport implements WalkTransport {
    static final TransportProtocol PROTO_SFTP = new TransportProtocol() {
        @Override
        public String getName() {
            return JGitText.get().transportProtoSFTP;
        }

        @Override
        public Set<String> getSchemes() {
            return Collections.singleton("sftp"); //$NON-NLS-1$
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
        public Transport open(URIish uri, Repository local, String remoteName)
                throws NotSupportedException {
            return new TransportSftp(local, uri);
        }
    };

    TransportSftp(Repository local, URIish uri) {
        super(local, uri);
    }

    @Override
    public FetchConnection openFetch() throws TransportException {
        final SftpObjectDB c = new SftpObjectDB(uri.getPath());
        final WalkFetchConnection r = new WalkFetchConnection(this, c);
        r.available(c.readAdvertisedRefs());
        return r;
    }

    @Override
    public PushConnection openPush() throws TransportException {
        final SftpObjectDB c = new SftpObjectDB(uri.getPath());
        final WalkPushConnection r = new WalkPushConnection(this, c);
        r.available(c.readAdvertisedRefs());
        return r;
    }

    FtpChannel newSftp() throws IOException {
        FtpChannel channel = getSession().getFtpChannel();
        channel.connect(getTimeout(), TimeUnit.SECONDS);
        return channel;
    }

    class SftpObjectDB extends WalkRemoteObjectDatabase {
        private final String objectsPath;

        private FtpChannel ftp;

        SftpObjectDB(String path) throws TransportException {
            if (path.startsWith("/~")) //$NON-NLS-1$
                path = path.substring(1);
            if (path.startsWith("~/")) //$NON-NLS-1$
                path = path.substring(2);
            try {
                ftp = newSftp();
                ftp.cd(path);
                ftp.cd(OBJECTS);
                objectsPath = ftp.pwd();
            } catch (FtpChannel.FtpException f) {
                throw new TransportException(MessageFormat.format(
                        JGitText.get().cannotEnterObjectsPath, path,
                        f.getMessage()), f);
            } catch (IOException ioe) {
                close();
                throw new TransportException(uri, ioe.getMessage(), ioe);
            }
        }

        SftpObjectDB(SftpObjectDB parent, String p)
                throws TransportException {
            try {
                ftp = newSftp();
                ftp.cd(parent.objectsPath);
                ftp.cd(p);
                objectsPath = ftp.pwd();
            } catch (FtpChannel.FtpException f) {
                throw new TransportException(MessageFormat.format(
                        JGitText.get().cannotEnterPathFromParent, p,
                        parent.objectsPath, f.getMessage()), f);
            } catch (IOException ioe) {
                close();
                throw new TransportException(uri, ioe.getMessage(), ioe);
            }
        }

        @Override
        URIish getURI() {
            return uri.setPath(objectsPath);
        }

        @Override
        Collection<WalkRemoteObjectDatabase> getAlternates() throws IOException {
            try {
                return readAlternates(INFO_ALTERNATES);
            } catch (FileNotFoundException err) {
                return null;
            }
        }

        @Override
        WalkRemoteObjectDatabase openAlternate(String location)
                throws IOException {
            return new SftpObjectDB(this, location);
        }

        @Override
        Collection<String> getPackNames() throws IOException {
            final List<String> packs = new ArrayList<>();
            try {
                Collection<FtpChannel.DirEntry> list = ftp.ls("pack"); //$NON-NLS-1$
                Set<String> files = list.stream()
                        .map(FtpChannel.DirEntry::getFilename)
                        .collect(Collectors.toSet());
                HashMap<String, Long> mtimes = new HashMap<>();

                for (FtpChannel.DirEntry ent : list) {
                    String n = ent.getFilename();
                    if (!n.startsWith("pack-") || !n.endsWith(".pack")) { //$NON-NLS-1$ //$NON-NLS-2$
                        continue;
                    }
                    String in = n.substring(0, n.length() - 5) + ".idx"; //$NON-NLS-1$
                    if (!files.contains(in)) {
                        continue;
                    }
                    mtimes.put(n, Long.valueOf(ent.getModifiedTime()));
                    packs.add(n);
                }

                Collections.sort(packs,
                        (o1, o2) -> mtimes.get(o2).compareTo(mtimes.get(o1)));
            } catch (FtpChannel.FtpException f) {
                throw new TransportException(
                        MessageFormat.format(JGitText.get().cannotListPackPath,
                                objectsPath, f.getMessage()),
                        f);
            }
            return packs;
        }

        @Override
        FileStream open(String path) throws IOException {
            try {
                return new FileStream(ftp.get(path));
            } catch (FtpChannel.FtpException f) {
                if (f.getStatus() == FtpChannel.FtpException.NO_SUCH_FILE) {
                    throw new FileNotFoundException(path);
                }
                throw new TransportException(MessageFormat.format(
                        JGitText.get().cannotGetObjectsPath, objectsPath, path,
                        f.getMessage()), f);
            }
        }

        @Override
        void deleteFile(String path) throws IOException {
            try {
                ftp.delete(path);
            } catch (FtpChannel.FtpException f) {
                throw new TransportException(MessageFormat.format(
                        JGitText.get().cannotDeleteObjectsPath, objectsPath,
                        path, f.getMessage()), f);
            }

            // Prune any now empty directories.
            //
            String dir = path;
            int s = dir.lastIndexOf('/');
            while (s > 0) {
                try {
                    dir = dir.substring(0, s);
                    ftp.rmdir(dir);
                    s = dir.lastIndexOf('/');
                } catch (IOException je) {
                    // If we cannot delete it, leave it alone. It may have
                    // entries still in it, or maybe we lack write access on
                    // the parent. Either way it isn't a fatal error.
                    //
                    break;
                }
            }
        }

        @Override
        OutputStream writeFile(String path, ProgressMonitor monitor,
                               String monitorTask) throws IOException {
            Throwable err = null;
            try {
                return ftp.put(path);
            } catch (FileNotFoundException e) {
                mkdir_p(path);
            } catch (FtpChannel.FtpException je) {
                if (je.getStatus() == FtpChannel.FtpException.NO_SUCH_FILE) {
                    mkdir_p(path);
                } else {
                    err = je;
                }
            }
            if (err == null) {
                try {
                    return ftp.put(path);
                } catch (IOException e) {
                    err = e;
                }
            }
            throw new TransportException(
                    MessageFormat.format(JGitText.get().cannotWriteObjectsPath,
                            objectsPath, path, err.getMessage()),
                    err);
        }

        @Override
        void writeFile(String path, byte[] data) throws IOException {
            final String lock = path + LOCK_SUFFIX;
            try {
                super.writeFile(lock, data);
                try {
                    ftp.rename(lock, path);
                } catch (IOException e) {
                    throw new TransportException(MessageFormat.format(
                            JGitText.get().cannotWriteObjectsPath, objectsPath,
                            path, e.getMessage()), e);
                }
            } catch (IOException err) {
                try {
                    ftp.rm(lock);
                } catch (IOException e) {
                    // Ignore deletion failure, we are already
                    // failing anyway.
                }
                throw err;
            }
        }

        private void mkdir_p(String path) throws IOException {
            final int s = path.lastIndexOf('/');
            if (s <= 0)
                return;

            path = path.substring(0, s);
            Throwable err = null;
            try {
                ftp.mkdir(path);
                return;
            } catch (FileNotFoundException f) {
                mkdir_p(path);
            } catch (FtpChannel.FtpException je) {
                if (je.getStatus() == FtpChannel.FtpException.NO_SUCH_FILE) {
                    mkdir_p(path);
                } else {
                    err = je;
                }
            }
            if (err == null) {
                try {
                    ftp.mkdir(path);
                    return;
                } catch (IOException e) {
                    err = e;
                }
            }
            throw new TransportException(MessageFormat.format(
                    JGitText.get().cannotMkdirObjectPath, objectsPath, path,
                    err.getMessage()), err);
        }

        Map<String, Ref> readAdvertisedRefs() throws TransportException {
            final TreeMap<String, Ref> avail = new TreeMap<>();
            readPackedRefs(avail);
            readRef(avail, ROOT_DIR + Constants.HEAD, Constants.HEAD);
            readLooseRefs(avail, ROOT_DIR + "refs", "refs/"); //$NON-NLS-1$ //$NON-NLS-2$
            return avail;
        }

        private void readLooseRefs(TreeMap<String, Ref> avail, String dir,
                                   String prefix) throws TransportException {
            final Collection<FtpChannel.DirEntry> list;
            try {
                list = ftp.ls(dir);
            } catch (IOException e) {
                throw new TransportException(MessageFormat.format(
                        JGitText.get().cannotListObjectsPath, objectsPath, dir,
                        e.getMessage()), e);
            }

            for (FtpChannel.DirEntry ent : list) {
                String n = ent.getFilename();
                if (".".equals(n) || "..".equals(n)) //$NON-NLS-1$ //$NON-NLS-2$
                    continue;

                String nPath = dir + "/" + n; //$NON-NLS-1$
                if (ent.isDirectory()) {
                    readLooseRefs(avail, nPath, prefix + n + "/"); //$NON-NLS-1$
                } else {
                    readRef(avail, nPath, prefix + n);
                }
            }
        }

        private Ref readRef(TreeMap<String, Ref> avail, String path,
                            String name) throws TransportException {
            final String line;
            try (BufferedReader br = openReader(path)) {
                line = br.readLine();
            } catch (FileNotFoundException noRef) {
                return null;
            } catch (IOException err) {
                throw new TransportException(MessageFormat.format(
                        JGitText.get().cannotReadObjectsPath, objectsPath, path,
                        err.getMessage()), err);
            }

            if (line == null) {
                throw new TransportException(
                        MessageFormat.format(JGitText.get().emptyRef, name));
            }
            if (line.startsWith("ref: ")) { //$NON-NLS-1$
                final String target = line.substring("ref: ".length()); //$NON-NLS-1$
                Ref r = avail.get(target);
                if (r == null)
                    r = readRef(avail, ROOT_DIR + target, target);
                if (r == null)
                    r = new ObjectIdRef.Unpeeled(Storage.NEW, target, null);
                r = new SymbolicRef(name, r);
                avail.put(r.getName(), r);
                return r;
            }

            if (ObjectId.isId(line)) {
                final Ref r = new ObjectIdRef.Unpeeled(loose(avail.get(name)),
                        name, ObjectId.fromString(line));
                avail.put(r.getName(), r);
                return r;
            }

            throw new TransportException(
                    MessageFormat.format(JGitText.get().badRef, name, line));
        }

        private Storage loose(Ref r) {
            if (r != null && r.getStorage() == Storage.PACKED) {
                return Storage.LOOSE_PACKED;
            }
            return Storage.LOOSE;
        }

        @Override
        void close() {
            if (ftp != null) {
                try {
                    if (ftp.isConnected()) {
                        ftp.disconnect();
                    }
                } finally {
                    ftp = null;
                }
            }
        }
    }
}
