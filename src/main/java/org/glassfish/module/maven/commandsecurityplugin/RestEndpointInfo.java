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

/**
 *
 * @author tjquinn
 */
public class RestEndpointInfo {
    private String configBeanClassName;
    private String opType;
    private String path;
    private boolean useForAuthorization = false;
    
    RestEndpointInfo(final String configBeanClassName, final String path, 
            final String opType, final boolean useForAuthorization) {
        this.configBeanClassName = configBeanClassName;
        this.opType = opType == null ? "GET" : opType;
        this.path = path == null ? "" : path;
        this.useForAuthorization = useForAuthorization;
    }

    String configBeanClassName() {
        return configBeanClassName;
    }
    
    String path() {
        return path;
    }
    
    String opType() {
        return opType;
    }
    
    boolean useForAuthorization() {
        return useForAuthorization;
    }
    
    @Override
    public String toString() {
        return "@RestEndpoint(configBean=" + configBeanClassName + ",path=" + path + ", opType=" + opType + ")";
    }
    
    
}
