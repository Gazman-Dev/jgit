/*
 * Copyright (C) 2011, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import org.eclipse.jgit.events.RepositoryEvent;

/**
 * Describes a change to the list of packs in a
 * {@link DfsRepository}.
 */
public class DfsPacksChangedEvent
        extends RepositoryEvent<DfsPacksChangedListener> {
    @Override
    public Class<DfsPacksChangedListener> getListenerType() {
        return DfsPacksChangedListener.class;
    }

    @Override
    public void dispatch(DfsPacksChangedListener listener) {
        listener.onPacksChanged(this);
    }
}
