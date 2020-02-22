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
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Properties;
import org.apache.maven.model.Developer;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Verifies that all inhabitants in the module that are commands also take care
 * of authorization, issuing warnings or failing the build (configurable) if
 * any do not.
 * <p>
 * The mojo has to analyze not only the inhabitants but potentially also 
 * their ancestor classes.  To improve performance across multiple modules in 
 * the same build the mojo stores information about classes known to be commands and
 * classes known not to be commands in the maven session. 
 * 
 * @author tjquinn
 */
@Mojo(name="check", threadSafe=true, defaultPhase=LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution=ResolutionScope.COMPILE_PLUS_RUNTIME)
public class CheckMojo extends CommonMojo {
    
    /**
     * Whether failures are fatal to the build.
     */
    @Parameter(property="command-security-maven-plugin.isFailureFatal", defaultValue="true")
    private String isFailureFatal;
    
    /**
     * Path to which to print a violation summary wiki table.  If empty,
     * print no table.
     */
    @Parameter(property="command-security-maven-plugin.violationWikiPath", defaultValue="")
    protected String violationWikiPath;
    
    /**
     * Path to properties file listing owners of modules.
     * 
     * The format is (moduleId).owner=(owner name)
     *               (moduleId).notes=(notes) - if any
     */
    @Parameter(property="command-security-maven-plugin.moduleInfoPath", defaultValue="~/moduleInfo.txt")
    protected String moduleOwnersPath;
    
    private static final String WIKI_INFO_SET = "is-wiki-info-set";
    private static WikiOutputInfo wikiOutputInfo;
    
    private boolean isLastProject() {
        final List<MavenProject> projects = (List<MavenProject>) reactorProjects;
        return project.equals(projects.get(projects.size() - 1));
    }
    
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            initWikiOutputInfo();
        } catch (IOException ex) {
            throw new MojoFailureException("Error initializing output file", ex);
        }
        final TypeProcessor typeProcessor = new TypeProcessorImpl(this, project, isFailureFatal,
                isCheckAPIvsParse);
        typeProcessor.execute();
        
        final StringBuilder trace = typeProcessor.trace();
        
        if (trace != null) {
            getLog().debug(trace.toString());
        }
        if (typeProcessor.okClassNames() != null) {
            getLog().debug("Command classes with authorization: " + typeProcessor.okClassNames().toString());
        }
        final List<String> offendingClassNames = typeProcessor.offendingClassNames();
        if ( ! offendingClassNames.isEmpty()) {
            if (wikiOutputInfo != null) {
                try {
                    ensureViolationWikiTitleIsPresent();
                } catch (IOException ex) {
                    throw new MojoFailureException("Error opening output file and writing title", ex);
                }
                printViolationWikiRow(typeProcessor.offendingClassNames());
            }
            if (typeProcessor.isFailureFatal()) {
                getLog().error("Following command classes neither provide nor inherit authorization: " + offendingClassNames.toString());
                throw new MojoFailureException("Command class(es) with no authorization");
            } else {
                getLog().warn("Following command classes neither provide nor inherit authorization: " + offendingClassNames.toString());
            }
        }
        if (wikiOutputInfo != null && wikiOutputInfo.wikiWriter != null) {
            wikiOutputInfo.wikiWriter.flush();
        }
        if (isLastProject()) {
            if (wikiOutputInfo != null) {
                wikiOutputInfo.finish();
            }
        }
    }
    
    private void ensureViolationWikiTitleIsPresent() throws IOException {
        if (wikiOutputInfo != null) {
            wikiOutputInfo.ensureInitialized();
        }
    }
    
    private void printViolationWikiRow(final List<String> offendingClassNames) {
        final String output = 
                "| " + project.getGroupId() + ":" + project.getArtifactId() + 
                " |" + project.getName() + 
                " | " + relativeToTop(project.getBasedir()) + 
                " | " + formattedList(offendingClassNames) + 
                " | " + nameOrId(getLead()) +
                " |";
        wikiOutputInfo.wikiWriter.println(output);
    }
    
    private Developer getLead() {
        final List<Developer> devs = (List<Developer>) project.getDevelopers();
        Developer lead = (devs.isEmpty() ? null : devs.get(0));
        for (Developer d : (List<Developer>) project.getDevelopers()) {
            final List<String> roles = d.getRoles();
            if (roles != null && roles.contains("lead")) {
                lead = d;
            }
        }
        return lead;
    }
    
    private String nameOrId(final Developer d) {
        String result = "?";
        if (d != null) {
            if (d.getName() != null) {
                result = d.getName();
            } else if (d.getId() != null) {
                result = d.getId();
            }
        }
        return result;
    }
    private String formattedList(final List<String> strings) {
        final StringBuilder sb = new StringBuilder();
        for (String s : strings) {
            if (sb.length() > 0) {
                sb.append("\\\\\n");
            }
            sb.append(s);
        }
        return sb.toString();
    }
    
    private String relativeToTop(final File f) {
        return new File(session.getExecutionRootDirectory()).toURI().relativize(f.toURI()).toASCIIString();
    }
    
    private void initWikiOutputInfo() throws IOException {
        if (wikiOutputInfo == null) {
            /*
             * Don't set up the violations output if the user didn't ask for it.
             */
            if (violationWikiPath == null || violationWikiPath.isEmpty()) {
                return;
            }
            /*
             * Maven seems to load the mojo's class more than once sometimes, so
             * we can't assume that wikiOutputInfo being null means this is the
             * first time the mojo is being run.
             */
            final Properties p = session.getUserProperties();
            final String infoState = p.getProperty(WIKI_INFO_SET);
            if (infoState == null) {
                wikiOutputInfo = new WikiOutputInfo(true);
                p.setProperty(WIKI_INFO_SET, "unopened");
            } else {
                wikiOutputInfo = new WikiOutputInfo(infoState);
            }
        }
    }
    
    private class WikiOutputInfo {
        File wikiFile = null;
        PrintWriter wikiWriter = null;
        final boolean isNew;
        
        private WikiOutputInfo(final boolean isNew) throws IOException {
            this.isNew = isNew;
            if (violationWikiPath != null && ! violationWikiPath.isEmpty()) {
                wikiFile = new File(session.getExecutionRootDirectory(), violationWikiPath);
            }
        }
        
        private WikiOutputInfo(final String state) throws IOException {
            this(false);
            if ( ! state.equals("unopened")) {
                openWriter();
            }
        }
        
        private void openWriter() throws IOException {
            wikiWriter = new PrintWriter(new FileWriter(wikiFile, ! isNew));
            session.getUserProperties().setProperty(WIKI_INFO_SET, "open");
        }
        
        private void ensureInitialized() throws IOException {
            if (wikiWriter == null) {
                openWriter();
                wikiWriter.println("{table-plus}");
                wikiWriter.println("|| Module ID || Module Name || Path || Classes Needing Attention || Owner ||");
            }
        }
        
        private void finish() {
            if (wikiWriter != null) {
                final String state = session.getUserProperties().getProperty(WIKI_INFO_SET);
                if ( ! "unopened".equals(state)) {
                    wikiWriter.println("{table-plus}");
                    wikiWriter.close();
                }
            }
        }
    }
}
