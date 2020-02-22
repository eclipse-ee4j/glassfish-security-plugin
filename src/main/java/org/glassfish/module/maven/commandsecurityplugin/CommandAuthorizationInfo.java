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

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 * @author tjquinn
 */
public class CommandAuthorizationInfo {
    private final static String LINE_SEP = System.getProperty("line.separator");
    
    final AtomicBoolean hasRestAnno = new AtomicBoolean(false);
    final AtomicBoolean hasCommandLevelAccessRequiredAnno = new AtomicBoolean(false);
    final AtomicBoolean hasFieldLevelAccessRequiredAnno = new AtomicBoolean(false);
    final AtomicBoolean isAccessCheckProvider = new AtomicBoolean(false);
    final AtomicBoolean isLocal = new AtomicBoolean(false);
    
    private List<RestEndpointInfo> endpoints = new ArrayList<RestEndpointInfo>();
    
    private CommandAuthorizationInfo parent = null;
    
    private String genericMethodListActual = "";
    private String fullPath = "";
    private String genericAction = "";
    private Delegate delegate = null;
    
    private final List<ResourceAction> resourceActionPairs = new ArrayList<ResourceAction>();
    
    private static final List<String> GENERIC_ACTIONS_USING_FULL_GENERIC_SUBPATH = 
            new ArrayList<String>(Arrays.asList(new String[] {"read", "update", "delete"}));
    
    public void setDelegate(final String delegateClassName) {
        delegate = new Delegate(delegateClassName);
    }
    
    public void addRestEndpoint(final RestEndpointInfo endpoint) {
        endpoints.add(endpoint);
    }
    
    public void addResourceAction(final String resource, final String action,
            final String origin) {
        resourceActionPairs.add(new ResourceAction(resource, action, origin));
    }
    
    public List<ResourceAction> resourceActionPairs() {
        return resourceActionPairs;
    }
    
    public void overrideResourceActions(final List<ResourceAction> newPairs) {
        resourceActionPairs.clear();
        resourceActionPairs.addAll(newPairs);
    }
    
    public String genericSubpath(final String separator) {
        if (genericMethodListActual == null || genericMethodListActual.isEmpty()) {
            return "";
        }
        return fullPath;
    }
    
    public String genericSubpathPerAction(final String separator) {
        /*
         * Initial subpath is collection-name/type-name.  That happens to be 
         * what we want for 'create' but we'll adjust it for other operations.
         */
        String subpath = genericSubpath(separator);
        
        if (GENERIC_ACTIONS_USING_FULL_GENERIC_SUBPATH.contains(genericAction)) {
            subpath = subpath + separator + "$name";
        } else if (genericAction.equals("list")) {
            /*
             * 'list' uses the collection only.
             */
            subpath = subpath.substring(0, subpath.lastIndexOf('/'));
        }
        /*
         * If 
         */
        return subpath;
    }
    
    public String adjustedGenericAction() {
        if (genericAction.equals("list")) {
            return "read";
        }
        return genericAction;
    }
    
    public String genericAction() {
        return genericAction;
    }
    
    public void setGeneric(final String methodListActual, 
            final String methodName, 
            final String fullPath,
            final String action) {
        this.genericMethodListActual = methodListActual;
//        this.genericParentConfigured = parentConfigured;
        this.fullPath = fullPath;
        this.genericAction = action;
    }
    
    public List<RestEndpointInfo> restEndpoints() {
        return endpoints;
    }
    
    public void setParent(final CommandAuthorizationInfo parent) {
        this.parent = parent;
    }
    
    public void setLocal(final boolean local) {
        isLocal.set(local);
    }
    
    public boolean isLocalDeep() {
        return isLocal.get() || (parent != null ? parent.isLocalDeep() : false);
    }
    
    boolean isOK() {
        return (delegate != null) || hasRestAnno.get() || hasCommandLevelAccessRequiredAnno.get() || hasFieldLevelAccessRequiredAnno.get() || isAccessCheckProvider.get();
    }
    
    boolean isOKDeep() {
        return isOK() || (parent != null ? parent.isOKDeep() : false);
    }
    
    boolean isAccessCheckProvider() {
        return isAccessCheckProvider.get();
    }
    
    Delegate delegate() {
        return delegate;
    }
    
    private String name;
    private String className;
    private List<Param> params = new ArrayList<Param>();

    void setName(final String name) {
        this.name = name;
    }

    void addParam(final Param p) {
        params.add(p);
    }

    void setClassName(final String className) {
        this.className = className;
    }
    
    List<Param> params() {
        return params;
    }
    
    String name() {
        return name;
    }
    
    String className() {
        return className;
    }

    @Override
    public String toString() {
        return toString("", true);
    }

    
    public String toString(final String indent, final boolean isFull) {
        final StringBuffer sb = new StringBuffer();
        if (delegate != null) {
            sb.append(name).append(" delegates to ").append(delegate.delegateInternalClassName);
        } else {
            sb.append(name).append(" (").append(fullPath != null && ! fullPath.isEmpty() ? "[" + adjustedGenericAction() + "] " + genericSubpathPerAction("/") : className).append(/* indent + */ ")");
        }
        if (isFull) {
            sb.append(LINE_SEP);
            final Deque<CommandAuthorizationInfo> levelsToProcess = new LinkedList<CommandAuthorizationInfo>();
            
            CommandAuthorizationInfo info = this;
            while (info != null) {
                levelsToProcess.addFirst(info);
                info = info.parent;
            }
            for (CommandAuthorizationInfo level : levelsToProcess) {
                for (Param p : level.params()) {
                    sb.append(indent).append("  ").append(p);
                }
            }
            for (RestEndpointInfo i : restEndpoints()) {
                if (i.useForAuthorization()) {
                    sb.append(LINE_SEP).append(indent).append("  ").append(i.toString());
                }
            }
                
        }
        return sb.append(LINE_SEP).toString();
    }
    
    static class Param {
        private String name;
        private String type;
        private Map<String,Object> values = new HashMap<String,Object>();
        
        Param(final String name, final String type) {
            this.name = name;
            this.type = type;
        }
        
        void setName(final String name) {
            this.name = name;
        }
        
        void setType (final String type) {
            this.type = type;
        }
        
        void addValue(final String name, final Object value) {
            values.put(name, value);
        }
        
        Map<String,Object> values() {
            return values;
        }
        
        boolean isOptional() {
            return booleanValue("optional");
        }
        
        boolean isPrimary() {
            return booleanValue("primary");
        }
        
        private boolean booleanValue(final String key) {
            boolean result = false;
            final Object v = values.get(key);
            if (v != null) {
                if (v instanceof Boolean) {
                    result = ((Boolean) v).booleanValue();
                }
            }
            return result;
        }
        
        String type() {
            return type;
        }
        
        @Override
        public String toString() {
            return (isOptional() ? "[" : "") + (isPrimary() ? "**" : "--") + name + friendlyType() + (isOptional() ? "]" : "");
        }
        
        private String friendlyType() {
            return (type.isEmpty() ? "" : " (" + type + ")");
        }
    }
    
    static class ResourceAction {
        String resource;
        String action;
        String origin;
        
        ResourceAction(final String resource, final String action, final String origin) {
            this.resource = resource;
            this.action = action;
            this.origin = origin;
        }
    }
    
    static class Delegate {
        String delegateInternalClassName;
        
        Delegate(final String delegateInternalClassName) {
            this.delegateInternalClassName = delegateInternalClassName;
        }
        
        String delegateInternalClassName() {
            return delegateInternalClassName;
        }
    }
}
