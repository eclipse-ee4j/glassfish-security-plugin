/*
 * Copyright (c) 2012, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.module.maven.commandsecurityplugin;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

/**
 *
 * @author tjquinn
 */
interface TypeProcessor {

    String KNOWN_AUTH_TYPES_NAME = "org.glassfish.api.admin.knownAuthTypes";
    String KNOWN_CRUD_CONFIG_BEAN_TYPES_NAME = "org.glassfish.api.admin.knownCRUDConfigBeansTypes";
    
    
    CommandAuthorizationInfo processType(final String internalClassName) throws MojoExecutionException, MojoFailureException;
//    CommandAuthorizationInfo processConfigBean(final String internalClassName) throws MojoFailureException, MojoExecutionException;
    void execute() throws MojoExecutionException, MojoFailureException;
    List<String> okClassNames();
    List<String> offendingClassNames();
    boolean isFailureFatal();
    StringBuilder trace();
    Collection<CommandAuthorizationInfo> authInfosThisModule();
    Map<String,TypeProcessorImpl.Inhabitant> configBeans();
}
