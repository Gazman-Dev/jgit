/*
 * Copyright (C) 2008, Florian Köberle <florianskarten@web.de> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.fnmatch;

final class WildCardHead extends AbstractHead {
    WildCardHead(boolean star) {
        super(star);
    }

    @Override
    protected final boolean matches(char c) {
        return true;
    }
}
