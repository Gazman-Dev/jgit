/*
 * Copyright (c) 2019 Matthias Sohn <matthias.sohn@sap.com>
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.util;

import org.eclipse.jgit.annotations.Nullable;

public class Monitoring {

	/**
	 * No-op method for registering an MBean.
	 *
	 * @param mbean      the MBean object to register
	 * @param metricName name of the JGit metric
	 * @return null, since JMX is not supported on Android
	 */
	public static @Nullable Object registerMBean(Object mbean, String metricName) {
		// JMX is not supported on Android; return null
		return null;
	}
}
