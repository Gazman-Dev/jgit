/*
 * Copyright (C) 2016, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.attributes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.eclipse.jgit.lib.Repository;

/**
 * The factory responsible for creating instances of
 * {@link FilterCommand}.
 *
 * @since 4.6
 */
public interface FilterCommandFactory {
	/**
	 * Create a new {@link FilterCommand}.
	 *
	 * @param db
	 *            the repository this command should work on
	 * @param in
	 *            the {@link InputStream} this command should read from
	 * @param out
	 *            the {@link OutputStream} this command should write to
	 * @return the created {@link FilterCommand}
	 * @throws IOException
	 *             thrown when the command constructor throws an
	 *             java.io.IOException
	 */
	FilterCommand create(Repository db, InputStream in, OutputStream out)
			throws IOException;

}
