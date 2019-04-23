/*
 * Copyright (c) 2013, 2018 Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2019 Payara Services Ltd.
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

import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 *
 * @author tjquinn
 */
public abstract class CommonMojo extends AbstractMojo {
    
    /**
     * The maven project.
     */
    @Parameter(property="project", required=true, readonly=true)
    protected MavenProject project;
    
    /** 
     * The Maven Session Object 
     */
    @Parameter(property="session", required=true, readonly=true)
    protected MavenSession session; 
    
    /**
     * The list of reactor projects
     * 
     */
    @Parameter(property="reactorProjects", required=true, readonly=true)
    protected List reactorProjects;
    
    @Parameter(property="command-security-maven-plugin.isCheckAPIvsParse", readonly=true)
    protected String isCheckAPIvsParse;
}
