/*
 * Copyright (c) 2012, 2018 Oracle and/or its affiliates. All rights reserved.
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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Displays a concise summary of each command found at the current level
 * in the project hierarchy or below.
 * <p>
 * This mojo uses basically the same technology as the CheckMojo to analyze
 * the inhabitants which define command-related services, except that rather 
 * than checking for authorization-related annotations or interfaces (as 
 * CheckMojo does) it just finds the commands and prints out the format of
 * the command.
 * 
 * @author tjquinn
 */
@Mojo(name="print",  threadSafe=true, defaultPhase=LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution=ResolutionScope.COMPILE_PLUS_RUNTIME)
public class PrintMojo extends CommonMojo {

    private final static String OUTPUT_PROP_NAME = "org.glassfish.command.security.output";
    private final static String OUTPUT_INDENT_PROP_NAME = "org.glassfish.command.security.output.indent";
    private final static String IS_ANY_OUTPUT_NAME = "org.glassfish.command.security.isAnyOutput";
    
    private final static String OVERRIDE_FILE = "commandSecurityOverride.txt";
    
    /**
     * Output type
     * Can be "summary" or "wiki" or "csv"
     * 
     */
    @Parameter(property="command-security-maven-plugin.output-type", readonly=true, defaultValue="summary")
    protected String outputType;
    
    private PrintWriter pw;
    AtomicBoolean isAnyOutput = new AtomicBoolean(false);
    private boolean isAnyOutputThisModule = false;
    
    StringBuilder indent;
    
    URI parentOfTopURI;
    
    Map<String,TypeProcessorImpl.Inhabitant> configBeans;
    
    private OverrideManager om;
    
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        isAnyOutput = getOrSet(IS_ANY_OUTPUT_NAME, isAnyOutput);
        parentOfTopURI = findParentOfTopURI();
        final TypeProcessorImpl typeProcessor = new TypeProcessorImpl(this, session, project);
        typeProcessor.execute();
        configBeans = typeProcessor.configBeans();
        
        om = initOverrideManager();
        final OutputFormatter outputFormatter = chooseOutputFormatter(outputType);
        pw = getPrintWriter();
        indent = getAndAdjustIndent();
        
        /*
         * Print header for this project only when we generate the first line
         * of other output.
         */
        for (CommandAuthorizationInfo info : typeProcessor.authInfosThisModule()) {
            /*
             * Weeds out superclasses that are not themselves services.
             */
            if (info.name() != null) {
                if ( ! isAnyOutputThisModule) {
                    outputFormatter.postOpen();
                    isAnyOutputThisModule = true;
                }
                outputFormatter.printCommandInfo(om.adjust(info));
            } else {
                getLog().debug("info.name() was null in project " + project.getName() + " for type " + info.className());
            }
        }
        outputFormatter.preClose();
        
        final StringBuilder trace = typeProcessor.trace();
        
        if (trace != null) {
            getLog().debug(trace.toString());
        }
        if (typeProcessor.okClassNames() != null) {
            getLog().debug("Command classes with authorization: " + typeProcessor.okClassNames().toString());
        }
        final List<String> offendingClassNames = typeProcessor.offendingClassNames();
        if ( ! offendingClassNames.isEmpty()) {
            if (typeProcessor.isFailureFatal()) {
                getLog().error("Following command classes neither provide nor inherit authorization: " + offendingClassNames.toString());
                throw new MojoFailureException("Command class(es) with no authorization");
            } else {
                getLog().warn("Following command classes neither provide nor inherit authorization: " + offendingClassNames.toString());
            }
        }
        restoreIndent(indent);
        pw.flush();
    }
    
    private OverrideManager initOverrideManager()  {
        final File overrideFile = new File(project.getBasedir(), OVERRIDE_FILE);
        return new OverrideManager(overrideFile, getLog());
        
    }
    
    private boolean isLastProject() {
        final List<MavenProject> projects = (List<MavenProject>) reactorProjects;
        return project.equals(projects.get(projects.size() - 1)) && isAnyOutput.get();
    }
    
    <T> T getOrSet(final String propertyName, final T value) {
        T result = (T) getSessionProperties().get(propertyName);
        if (result == null){
            result = value;
            getSessionProperties().put(propertyName, result);
        }
        return result;
    }
    
    
    private URI findParentOfTopURI() {
        File dir = project.getBasedir();
        File parentOfTopLevel = null;
        while (dir != null) {
            final File pom = new File(dir, "pom.xml");
            if ( ! pom.canRead()) {
                parentOfTopLevel = dir;
                break;
            }
            dir = dir.getParentFile();
        }
        return parentOfTopLevel.toURI();
    }
    
    private Properties getSessionProperties() {
        return session.getUserProperties();
    }
    private PrintWriter getPrintWriter() throws MojoFailureException {
        PrintWriter pw = (PrintWriter) getSessionProperties().get(OUTPUT_PROP_NAME);
        if (pw == null) {
            final MavenProject execRoot = getExecutionRootProject();
            final File outputFile = new File(execRoot.getBasedir(), "commandList.txt");
            try {
                pw = new PrintWriter(outputFile);
                getSessionProperties().put(OUTPUT_PROP_NAME, pw);
            } catch (FileNotFoundException ex) {
                throw new MojoFailureException("Unable to open command list output file " + outputFile.getAbsolutePath() + ex.getMessage());
            }
        }
        return pw;
    }
    
    private MavenProject getExecutionRootProject() {
        MavenProject execRoot = null;
        for (Object o : reactorProjects) {
            if (o instanceof MavenProject) {
                MavenProject p = (MavenProject) o;
                if (p.isExecutionRoot()) {
                    execRoot = p;
                }
            }
        }
        return execRoot;
    }
    
    private StringBuilder getAndAdjustIndent() {
        StringBuilder result = (StringBuilder) getSessionProperties().get(OUTPUT_INDENT_PROP_NAME);
        if (result == null) {
            result = new StringBuilder();
        }
        result.append("  ");
        getSessionProperties().put(OUTPUT_INDENT_PROP_NAME, result);
        return result;
    }
    
    private void restoreIndent(StringBuilder indent) {
        if (indent.length() > 0) {
            indent.delete(indent.length() - 2, indent.length());
        }
    }
    
    private OutputFormatter chooseOutputFormatter(final String outputType) {
        if (outputType.equals("summary")) {
            return new SummaryFormatter();
        } else if (outputType.equals("wiki")) {
            return new WikiFormatter();
        } else if (outputType.equals("csv")) {
            return new CSVFormatter();
        } else {
            getLog().warn("Unrecognized output type " + outputType + "; using human-readable instead");
            return new SummaryFormatter();
        }
    }
    private interface OutputFormatter {
        void postOpen();
        void printCommandInfo(CommandAuthorizationInfo authInfo);
        void preClose();
    }
    
    private class SummaryFormatter implements OutputFormatter {

        public void postOpen() {
            pw.println(indent.toString() + "=================================================================================");
            pw.println(indent.toString() + project.getName() + "(" + project.getBasedir() + ")");
            pw.println();
        }

        public void printCommandInfo(CommandAuthorizationInfo authInfo) {
            pw.println(indent.toString() + "  " + authInfo.toString(indent.toString() + "  ", true));
        }
        
        public void preClose() {
        }
    }
    
    private abstract class OneLineFormatter implements OutputFormatter {
        
        private final String sep;
        
        OneLineFormatter(final String sep) {
            this.sep = sep;
        }
        
        protected abstract void doPostOpen(); 
        public void postOpen() {
            if ( ! isAnyOutput.get()) {
                doPostOpen();
                isAnyOutput.set(true);
            }
        }
        
        String lastPart(final String s) {
            final int lastSlash = s.lastIndexOf('/');
            if (lastSlash != -1) {
                return s.substring(lastSlash + 1);
            } else {
                return s;
            }
        }
        
        public void printCommandInfo(CommandAuthorizationInfo authInfo) {
            final StringBuilder prefix = new StringBuilder(sep)
                    .append(project.getName()).append(sep)
                    .append(parentOfTopURI.relativize(project.getBasedir().toURI()).toASCIIString()).append(sep)
                    .append(authInfo.name()).append(sep);
            
            /*
             * If this command delegates its authorization to another class
             * then just report that class.
             */
            if (authInfo.delegate() != null) {
                final StringBuilder sb = new StringBuilder(prefix)
                        .append('?') /* resource */ .append(sep)
                        .append('?') /* action */ .append(sep)
                        .append("Delegates to ").append(authInfo.delegate().delegateInternalClassName().replace('/','.'))
                        .append(sep);
                pw.println(sb.toString());
            }
            
            /*
             * If this is from a generated CRUD command, display the 
             * generic CRUD command info.
             */
            if ( ! authInfo.genericAction().isEmpty()) {
                /*
                 * If this is a create operation, then the action is an update
                 * on the resource's parent.
                 */
                
//                String subpath = authInfo.genericSubpath("/");
//                String action = authInfo.genericAction();
//                if (authInfo.genericAction().equals("create")) {
//                    action = "update";
//                    subpath = subpath.substring(0, subpath.lastIndexOf('/'));
//                } else if (authInfo.genericAction().equals("list")) {
//                    action = "read";
//                    subpath = subpath.substring(0, subpath.lastIndexOf('/'));
//                }
                final StringBuilder sb = new StringBuilder(prefix)
                    .append(authInfo.genericSubpathPerAction("/")).append(sep)
                    .append(authInfo.adjustedGenericAction()).append(sep)
                    .append("CRUD").append(sep);
                pw.println(sb.toString());
            }
                
            for (RestEndpointInfo endpointInfo : authInfo.restEndpoints()) {
                if ( ! endpointInfo.useForAuthorization()) {
                    continue;
                }
                final TypeProcessorImpl.Inhabitant configBean = configBeans.get(endpointInfo.configBeanClassName().replace('/', '.'));
                if (configBean == null) {
                    getLog().error("Could not find config bean for RestEndpoint with config bean class name " + endpointInfo.configBeanClassName());
                    continue;
                }
                
                final StringBuilder sb = new StringBuilder(prefix)
                        .append(configBean.fullPath()).append(sep)
                        .append(Util.restOpTypeToAction(endpointInfo.opType())).append(sep)
                        .append("ReST").append(sep);
                pw.println(sb.toString());
            }
            
            for (CommandAuthorizationInfo.ResourceAction ra : authInfo.resourceActionPairs()) {
                final StringBuilder sb = new StringBuilder(prefix)
                        .append(ra.resource).append(sep)
                        .append(ra.action).append(sep)
                        .append(ra.origin).append(sep);
                pw.println(sb.toString());
            }
            
            /*
             * Handle if the command implements AccessCheckProvider.  We won't
             * try to figure out exactly what checks it provides, but we at
             * least want to display it in the output.
             */
            if (authInfo.isAccessCheckProvider()) {
                final StringBuilder sb = new StringBuilder(prefix)
                        .append("?").append(sep)
                        .append("?").append(sep)
                        .append("AccessCheckProvider").append(sep);
                pw.println(sb.toString());
            }
        }
        
        protected void doPreClose() {}
        
        public void preClose() {
            if (isLastProject() && isAnyOutput.get()) {
                doPreClose();
            }
        }
    }
    
    private class WikiFormatter extends OneLineFormatter {

        WikiFormatter() {
            super(" | ");
        }
        
        @Override
        public void doPostOpen() {
            pw.println("{table-plus}");
            pw.println("|| Module Name || Module Dir || Command Name || Resource || Action || Origin ||");
        }
        
        @Override
        public void doPreClose() {
            pw.println("{table-plus}");
        }
    }
    
    private class CSVFormatter extends OneLineFormatter {
        
        CSVFormatter() {
            super(",");
        }
        
        @Override
        public void doPostOpen() {
            pw.println("Module Name,Module Dir, Command Name,Resource,Action,Origin");
        }
    }
    
}
