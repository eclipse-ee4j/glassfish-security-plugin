/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.glassfish.hk2.utilities.DescriptorImpl;
import org.glassfish.module.maven.commandsecurityplugin.CommandAuthorizationInfo.Param;
import org.objectweb.asm.Type;

import static java.nio.charset.StandardCharsets.UTF_8;

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
 *
 */
public class TypeProcessorImpl implements TypeProcessor {

    static Properties shared = new Properties();

    private boolean isFailureFatal;
    private boolean isCheckAPIvsParse;

    private static final String KNOWN_NONCOMMAND_TYPES_NAME = "org.glassfish.api.admin.knownNonCommandTypes";
    private static final String PROCESSED_MODULES_NAME = "org.glassfish.api.admin.processedModules";
    private static final String CONFIG_BEANS_NAME = "org.glassfish.api.admin.configBeans";

    private static final String INHABITANTS_PATHS_PREFIX = "META-INF/hk2-locator/";
    private static final String[] INHABITANTS_PATHS = {"default", "tenant-scoped"};

//    private static final Pattern INHABITANT_DESCR_PATTERN = Pattern.compile("(\\w+)=([^:]+)(?:\\:(.+))*");
    private static final Pattern INHABITANT_IMPL_CLASS_PATTERN = Pattern.compile("\\[([^\\]]+)\\]");
    private static final Pattern INHABITANT_CONTRACTS_PATTERN = Pattern.compile("contract=\\{([^\\}]+)\\}");
    private static final Pattern INHABITANT_NAME_PATTERN = Pattern.compile("name=(.+)");
    private final static Pattern CONFIG_BEAN_METADATA_PREFIX_PATTERN = Pattern.compile(
            "metadata=target=\\{([^\\}]+)\\}(.*)"); //

    // ,<element-name>={class-name} or ,<element-name>={collection\:class-name}
    private final static Pattern CONFIG_BEAN_CHILD_PATTERN = Pattern.compile(
            ",(?:<([^>]+)>=\\{(?:collection\\\\:)?([^,}]+)[},])|(?:\\@[^}]+})|(?:key=[^}]+)|(?:keyed-as=[^}]+)");
    private static final Pattern GENERIC_COMMAND_INFO_PATTERN = Pattern.compile("metadata=MethodListActual=\\{([^\\}]+)\\},MethodName=\\{([^\\}]+)\\},ParentConfigured=\\{([^\\}]+)\\}");
    private static final Pattern CONFIG_BEAN_CHILD_NAME_KEY_PATTERN = Pattern.compile("<([^>]+)>");


    private static final String ADMIN_COMMAND_NAME = "org.glassfish.api.admin.AdminCommand";

    private static final String LINE_SEP = System.getProperty("line.separator");

    private static final String GENERIC_CREATE_COMMAND = "org.glassfish.config.support.GenericCreateCommand";
    private static final String GENERIC_DELETE_COMMAND = "org.glassfish.config.support.GenericDeleteCommand";
    private static final String GENERIC_LIST_COMMAND = "org.glassfish.config.support.GenericListCommand";
    private static final Set<String> GENERIC_CRUD_COMMAND_CLASS_NAMES =
            new HashSet<>(Arrays.asList(GENERIC_CREATE_COMMAND,
            GENERIC_DELETE_COMMAND,
            GENERIC_LIST_COMMAND));

    private static final List<String> EXTENSION_INTERNAL_NAMES = new ArrayList<>(Arrays.asList(
            "com/sun/enterprise/config/serverbeans/DomainExtension",
            "com/sun/enterprise/config/serverbeans/ConfigExtension",
            "com/oracle/cloudlogic/tenantmanager/entity/TenantExtension",
            "com/sun/enterprise/config/serverbeans/ApplicationExtension",
            "com/oracle/cloudlogic/tenantmanager/entity/TenantEnvironmentExtension"));

    private static final String CONFIG_BEAN_NAME = "org.jvnet.hk2.config.ConfigBean";
    private static final String CONFIG_BEAN_PROXY_NAME = "org.jvnet.hk2.config.ConfigBeanProxy";

    private static final Map<String,String> genericCommandNameToAction =
            initCommandNameToActionMap();

    private static Map<String,String> initCommandNameToActionMap() {
        final Map<String,String> result = new HashMap<String,String>();
        result.put(GENERIC_CREATE_COMMAND, "create");
        result.put(GENERIC_DELETE_COMMAND, "delete");
        result.put(GENERIC_LIST_COMMAND, "list");
        return result;
    }

    private URLClassLoader loader;
    private File buildDir;

    private StringBuilder trace = null;

    private Map<String,CommandAuthorizationInfo> knownCommandTypes = null;
    private Set<String> knownNonCommandTypes = null;

    private Collection<CommandAuthorizationInfo> authInfosThisModule = new ArrayList<CommandAuthorizationInfo>();

    private final List<String> offendingClassNames = new ArrayList<>();
    private List<String> okClassNames = null;

    private Set<URL> jarsProcessedForConfigBeans = null;

    private Map<String,Inhabitant> configBeans = null;

    private final AbstractMojo mojo;
    private final MavenProject project;


    TypeProcessorImpl(final AbstractMojo mojo,
            final MavenProject project) {
        this(mojo, project, false, false);
    }

    private TypeProcessorImpl(final AbstractMojo mojo,
            final MavenProject project,
            final boolean isFailureFatal,
            final boolean isCheckAPIvsParse) {
        this.mojo = mojo;
        this.project = project;
        this.isFailureFatal = isFailureFatal;
        this.isCheckAPIvsParse = isCheckAPIvsParse;
    }

    TypeProcessorImpl(final AbstractMojo mojo,
            final MavenProject project,
            final String isFailureFatalSetting,
            final String isCheckAPIvsParse) {
        this(mojo, project, Boolean.parseBoolean(isFailureFatalSetting),
                Boolean.parseBoolean(isCheckAPIvsParse));
    }

    @Override
    public Map<String,Inhabitant> configBeans() {
        return configBeans;
    }

    private Log getLog() {
        return mojo.getLog();
    }

    /**
     * Processes a type (class or interface), analyzing its byte code to see if
     * it is a command and, if so, checking for authorization-related annos
     * and interface implementations, finally recording whether it is a known command
     * or a known non-command (to speed up analysis of other types that might
     * refer to this one).
     *
     * @param internalClassName
     * @return the command authorization info for the class if it is a command, null otherwise
     * @throws MojoExecutionException
     */
    @Override
    public CommandAuthorizationInfo processType(final String internalClassName) throws MojoFailureException, MojoExecutionException {
        return processType(internalClassName, false);
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        this.trace = (getLog().isDebugEnabled() ? new StringBuilder() : null);
        this.okClassNames = (getLog().isDebugEnabled() ? new ArrayList<>() : null);

        buildDir = new File(project.getBuild().getOutputDirectory());
        try {
            setUpKnownTypes();
        } catch (Exception ex) {
            throw new MojoExecutionException("Error retrieving information about earlier processing results" + ex.toString());
        }

        /*
         * Set up a class loader that knows about this project's dependencies.
         * We don't actually load classes; we use the loader's getResourceAsStream
         * to get a class's byte code for analysis.
         */
        loader = createClassLoader();
        try {
            loadConfigBeans();
        } catch (Exception ex) {
            throw new MojoExecutionException("Error loading config beans", ex);
        }

        final Collection<Inhabitant> inhabitants;
        try {
            inhabitants = findInhabitantsInModule();
        } catch (IOException ex) {
            throw new MojoExecutionException("Error searching inhabitants for commands", ex);
        }
        final Collection<Inhabitant> commandInhabitants = findCommandInhabitants(inhabitants);
        for (Inhabitant i : commandInhabitants) {
            authInfosThisModule.add(processType(i));
        }

        if (trace != null) {
            getLog().debug(trace.toString());
        }
    }

    private void loadConfigBeans() throws MalformedURLException, IOException {
        for (URL url : loader.getURLs()) {
            if ( ! jarsProcessedForConfigBeans.contains(url)) {
                getLog().debug("Starting to load configBeans from " + url.toExternalForm());
                loadConfigBeansFromJar(url);
                jarsProcessedForConfigBeans.add(url);
            }
        }
    }

    private void loadConfigBeansFromJar(final URL url) throws MalformedURLException, IOException {
        for (String inhabitantsPath : INHABITANTS_PATHS) {
            final String fullPath = INHABITANTS_PATHS_PREFIX + inhabitantsPath;
            final String fullURL;
            final URL inhabitantsURL;
            if (url.getPath().endsWith(".jar")) {
                fullURL = "jar:file:" + url.getPath() + "!/" + fullPath;
                inhabitantsURL = new URL(fullURL);
            } else {
                fullURL = fullPath;
                inhabitantsURL = new URL(url, fullURL);
            }

            try {
                loadConfigBeans(url, inhabitantsURL);
            } catch (FileNotFoundException ex) {
                // This must means that the JAR does not contain an inhabitants file.  Continue.
                continue;
            }
        }
    }

    private void loadConfigBeans(final URL url, final URL inhabitantsURL) throws IOException {
        /*
         * As a side effect, findInhabitantsInModule adds config beans in the
         * specified input to configBeans.
         */
        final List<Inhabitant> inhabitants;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new BufferedInputStream(inhabitantsURL.openStream()), UTF_8))) {
            inhabitants = findInhabitantsInModule(reader);
        }
        if (!isCheckAPIvsParse) {
            return;
        }
        Map<String, Inhabitant> preBeans = new HashMap<>(configBeans);
        Set<Inhabitant> beansAddedByNew = new HashSet<>(configBeans.values());
        beansAddedByNew.removeAll(preBeans.values());

        final List<Inhabitant> old;
        try (InputStreamReader reader = new InputStreamReader(new BufferedInputStream(inhabitantsURL.openStream()), UTF_8)) {
            old = findInhabitantsInModule(reader);
        }
        Set<Inhabitant> beansAddedByOld = new HashSet<>(configBeans.values());
        beansAddedByOld.removeAll(preBeans.values());
        if (beansAddedByOld.equals(beansAddedByNew)) {
            getLog().info("ConfigBeans match for file " + url.toExternalForm());
        } else {
            Set<Inhabitant> beansAddedByNewNotOld = beansAddedByNew;
            beansAddedByNewNotOld.removeAll(beansAddedByOld);
            Set<Inhabitant> beansAddedByOldNotNew = beansAddedByOld;
            beansAddedByOldNotNew.removeAll(beansAddedByNew);
            throw new RuntimeException("Beans added mismatch for URL " + url.toExternalForm()
                + "\n  added by new not old: " + beansAddedByNewNotOld.toString() + "\n  added by old not new: "
                + beansAddedByOldNotNew.toString());
        }
        if (old.equals(inhabitants)) {
            return;
        }
        Set<Inhabitant> inNewerNotInOld = new HashSet<>(inhabitants);
        inNewerNotInOld.removeAll(old);
        Set<Inhabitant> inOldNotInNewer = new HashSet<>(old);
        inOldNotInNewer.removeAll(inhabitants);
        throw new RuntimeException(
            "Inhabitants mismatch for file " + inhabitantsURL.toExternalForm() + "\n  extra in old: "
                + inOldNotInNewer.toString() + "\n  extra in new: " + inNewerNotInOld.toString());
    }

    @Override
    public Collection<CommandAuthorizationInfo> authInfosThisModule() {
        return authInfosThisModule;
    }

    @Override
    public List<String> okClassNames() {
        return okClassNames;
    }

    @Override
    public List<String> offendingClassNames() {
        return offendingClassNames;
    }

    @Override
    public boolean isFailureFatal() {
        return isFailureFatal;
    }

    @Override
    public StringBuilder trace() {
        return trace;
    }


    private CommandAuthorizationInfo processType(final Inhabitant i) throws MojoFailureException, MojoExecutionException {
        /*
         * If this inhabitant is generated as a CRUD command then we do not
         * need to analyze the byte code - what we need to know is already
         * present in the inhabitant data.
         */
        if ( ! GENERIC_CRUD_COMMAND_CLASS_NAMES.contains(i.className)) {
            return processType(i.className, true);
        }
        final CommandAuthorizationInfo info = new CommandAuthorizationInfo();
        final Param primary = new Param("name", "");
        primary.addValue("primary", Boolean.TRUE);

        info.addParam(primary);
        info.setName(i.serviceName);
        info.setClassName(i.className);
        info.setLocal(false);
        info.setGeneric(i.methodListActual, i.methodName, i.fullPath(), i.action);
        return info;
    }

    private CommandAuthorizationInfo processType(final String internalClassName, final boolean isInhabitant) throws MojoExecutionException, MojoFailureException {
        /*
         * If we have already processed this type, use the earlier result if it
         * is a command and if it is not a command, return null immediately.
         */
        if (knownCommandTypes.containsKey(internalClassName)) {
            getLog().debug("Recognized previously-IDd class as command: " + internalClassName);
            return knownCommandTypes.get(internalClassName);
        }
        if (knownNonCommandTypes.contains(internalClassName)) {
            getLog().debug("Recognized previously-IDd class as non-command: " + internalClassName);
            return null;
        }

        /*
         * Find the byte code for this class so we can analyze it.
         */
        final String resourcePath = internalClassName.replace('.','/') + ".class";
        final InputStream is = loader.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new MojoFailureException("Cannot locate byte code for inhabitant class " + resourcePath);
        }
        try {
            final TypeAnalyzer typeAnalyzer = new TypeAnalyzer(is, knownCommandTypes, this);
            typeAnalyzer.setTrace(trace);
            typeAnalyzer.run();
            if (trace != null) {
                getLog().debug(trace.toString());
                trace = new StringBuilder();
            }
            final CommandAuthorizationInfo authInfo = typeAnalyzer.commandAuthInfo();
            if (authInfo != null) {
                if (trace != null) {
                    trace.append(LINE_SEP).append("Adding ").append(internalClassName).append(" to knownCommandTypes");
                }
                knownCommandTypes.put(internalClassName, authInfo);
            } else {
                if (trace != null) {
                    trace.append(LINE_SEP).append("Adding ").append(internalClassName).append(" to knownNonCommandTypes");
                }
                knownNonCommandTypes.add(internalClassName);
            }
            /*
                * Log our decision about whether this class is OK only if this
                * is an inhabitant.  (This method might have been invoked to analyze a
                * superclass, and if the superclass was not listed as an
                * inhabitant we don't need to report whether it has authorization
                * info or not).
                */
            if (isInhabitant) {
                if ((authInfo == null) || ! authInfo.isOKDeep()) {
                    offendingClassNames.add(internalClassName);
                } else {
                    if (okClassNames != null) {
                        okClassNames.add(internalClassName);
                    }
                }
            }
            return typeAnalyzer.commandAuthInfo();
        } catch (Exception ex) {
            throw new MojoExecutionException("Error analyzing " + internalClassName, ex);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ex) {
                    getLog().warn("Error closing input stream for " + internalClassName + "; " + ex.getLocalizedMessage());
                }
            }
        }

    }

    private void setUpKnownTypes() {
        knownCommandTypes = getOrCreate(KNOWN_AUTH_TYPES_NAME, knownCommandTypes);
        knownNonCommandTypes = getOrCreate(KNOWN_NONCOMMAND_TYPES_NAME, knownNonCommandTypes);
        jarsProcessedForConfigBeans = getOrCreate(PROCESSED_MODULES_NAME, jarsProcessedForConfigBeans);
        configBeans = getOrCreate(CONFIG_BEANS_NAME, configBeans);
    }

    private <T,U> Map<T,U> getOrCreate(final String propertyName, final Map<T,U> m) {
        Map<T,U> collection = (Map<T,U>) getSessionProperties().get(propertyName);
        if (collection == null) {
            collection = new HashMap<T,U>();
            getSessionProperties().put(propertyName, collection);
        }
        return collection;
    }

    private <T> Set<T> getOrCreate(final String propertyName, final Set<T> s) {
        Set<T> collection = (Set<T>) getSessionProperties().get(propertyName);
        if (collection == null) {
            collection = new HashSet<T>();
            getSessionProperties().put(propertyName, collection);
        }
        return collection;
    }

    private Collection<Inhabitant> findCommandInhabitants(final Collection<Inhabitant> inhabitants) {
        final List<Inhabitant> result = new ArrayList<Inhabitant>();
        for (Inhabitant inh : inhabitants) {
            if (inh.contracts.contains(ADMIN_COMMAND_NAME)) {
                if (trace != null) {
                    trace.append(LINE_SEP).append(" Inhabitant ").append(inh.className).append(" seems to be a command");
                }
                result.add(inh);
            }
        }
        return result;
    }

    private Properties getSessionProperties() {
        return shared;
    }


    private List<Inhabitant> findInhabitantsInModule() throws IOException {
        final List<Inhabitant> inhabitants = new ArrayList<Inhabitant>();
        for (String inhabitantsPath : INHABITANTS_PATHS) {
            final String fullPath = INHABITANTS_PATHS_PREFIX + inhabitantsPath;
            final File inhabFile = new File(buildDir, fullPath);
            inhabitants.addAll(findInhabitantsInModule(inhabFile));
        }
        return inhabitants;
    }

    private List<Inhabitant> findInhabitantsInModule(final File inhabFile) throws FileNotFoundException, IOException {
        if (!inhabFile.canRead()) {
            getLog().debug("Cannot read " + inhabFile.getAbsolutePath());
            return Collections.emptyList();
        }

        final List<Inhabitant> inhabitants;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new BufferedInputStream(new FileInputStream(inhabFile)), UTF_8))) {
            inhabitants = findInhabitantsInModule(reader);
        }
        if (!isCheckAPIvsParse) {
            return inhabitants;
        }
        final Map<String,Inhabitant> preBeans = new HashMap<>(configBeans);
        final List<Inhabitant> old;
        try (InputStreamReader reader = new InputStreamReader(new BufferedInputStream(new FileInputStream(inhabFile)), UTF_8)) {
            old = findInhabitantsInModule(reader);
        }
        final Set<Inhabitant> beansAddedByOld = new HashSet<>(configBeans.values());
        beansAddedByOld.removeAll(preBeans.values());

        final Set<Inhabitant> beansAddedByNew = new HashSet<>(configBeans.values());
        beansAddedByNew.removeAll(preBeans.values());
        if (beansAddedByOld.equals(beansAddedByNew)) {
            getLog().info("ConfigBeans match for file " + inhabFile.getAbsolutePath());
        } else {
            final Set<Inhabitant> beansAddedByNewNotOld = beansAddedByNew;
            beansAddedByNewNotOld.removeAll(beansAddedByOld);
            final Set<Inhabitant> beansAddedByOldNotNew = beansAddedByOld;
            beansAddedByOldNotNew.removeAll(beansAddedByNew);
            throw new RuntimeException("Beans added mismatch for URL " + inhabFile.getAbsolutePath()
                + "\n  added by new not old: " + beansAddedByNewNotOld.toString() + "\n  added by old not new: "
                + beansAddedByOldNotNew.toString());
        }
        if (old.equals(inhabitants)) {
            return inhabitants;
        }
        final Set<Inhabitant> inNewerNotInOld = new HashSet<>(inhabitants);
        inNewerNotInOld.removeAll(old);
        final Set<Inhabitant> inOldNotInNewer = new HashSet<>(old);
        inOldNotInNewer.removeAll(inhabitants);
        throw new RuntimeException(
            "Inhabitants mismatch for file " + inhabFile.getAbsolutePath() + "\n  extra in old: "
                + inOldNotInNewer.toString() + "\n  extra in new: " + inNewerNotInOld.toString());
    }

    private List<Inhabitant> findInhabitantsInModule(final BufferedReader br) throws IOException {
        final List<Inhabitant> result = new ArrayList<Inhabitant>();
        DescriptorImpl di;
        while ((di = new DescriptorImpl()).readObject(br)) {
            final Inhabitant inhabitant = new Inhabitant(di.getImplementation());
            inhabitant.contracts = new ArrayList<>(di.getAdvertisedContracts());
            inhabitant.serviceName = di.getName();
            inhabitant.methodListActual = getFirstIfAny(di.getMetadata(), "MethodListActual");
            inhabitant.methodName = getFirstIfAny(di.getMetadata(), "MethodName");
            inhabitant.parentConfigured = getParentConfigured(di);
            if (inhabitant.methodName != null) {
                getLog().debug("Recognized generic command " + inhabitant.serviceName);
                inhabitant.action = genericCommandNameToAction.get(inhabitant.className);
                Inhabitant configBeanParent = configBeans.get(inhabitant.parentConfigured);
                if (configBeanParent == null) {
                    configBeanParent = new Inhabitant(inhabitant.parentConfigured);
                    configBeans.put(configBeanParent.className, configBeanParent);
                    getLog().debug("Created parent bean " + configBeanParent.className + " for target bean " + inhabitant.methodListActual);
                } else {
                    getLog().debug("Found parent bean " + configBeanParent.className + " for target bean " + inhabitant.methodListActual);
                }

                Inhabitant configBean = configBeans.get(inhabitant.methodListActual);
                if (configBean == null) {
                    configBean = new Inhabitant(inhabitant.methodListActual);
                    configBeans.put(configBean.className, configBean);
                    getLog().debug("Created new config bean for " + configBean.className);
                } else {
                    getLog().debug("Found existing config bean for " + configBean.className);
                }
                configBean.parent = configBeanParent;
                inhabitant.configBeanForCommand = configBean;
            }
            final List<String> targets = di.getMetadata().get("target");
            if (targets != null && targets.size() > 0) {
                final String configBeanClassName = targets.get(0);
                getLog().debug("Recognized " + configBeanClassName + " as a config bean");
                Inhabitant configBean = configBeans.get(configBeanClassName);
                if (configBean == null) {
                    configBean = new Inhabitant(configBeanClassName);
                }
                /*
                 * Handle the parent.
                 */
                if (configBean.parent == null) {
                    Inhabitant parent = findParent(configBean);
                    if (parent != null) {
                        configBean.parent = parent;
                    }
                }
                configBeans.put(configBeanClassName, configBean);

                /*
                 * Search for and process child elements.
                 */
                for (Map.Entry<String,List<String>> entry : di.getMetadata().entrySet()) {
                    final Matcher m = CONFIG_BEAN_CHILD_NAME_KEY_PATTERN.matcher(entry.getKey());
                    if (m.matches()) {
                        /*
                         * Group 1 (from the key) is the child name and the first
                         * part of the value is the child type which might have
                         * the prefix "collection:".
                         */
                        String childClassName = entry.getValue().get(0);
                        String subpathInParent = m.group(1);
                        final boolean isCollection = childClassName.startsWith("collection:");
                        if (isCollection) {
                            childClassName = childClassName.substring("collection:".length());
                        }
                        getLog().debug("Identified " + childClassName + " as child " + (isCollection ? "collection " : "") + subpathInParent + " of " + configBean.className);
                        Inhabitant childInh = configBeans.get(childClassName);
                        if (childInh == null) {
                            childInh = new Inhabitant(childClassName);
                            configBeans.put(childClassName, childInh);
                            getLog().debug("Added child inhabitant to configBeans");
                        } else {
                            getLog().debug("Found child as previously-defined config bean");
                        }
                        getLog().debug("Assigning " + configBean.className + " as parent of " + childInh.className);
                        childInh.parent = configBean;

                        if (configBean.children == null) {
                            configBean.children = new HashMap<String,Child>();
                        }
                        Child child = configBean.children.get(childClassName);
                        if (child == null) {
                            child = new Child(subpathInParent);
                            configBean.children.put(childClassName, child);
                            getLog().debug("Adding config bean " + childClassName + " as child " + subpathInParent + " to config bean " + configBean.className);
                        }
                    }
                }
            }
            result.add(inhabitant);
        }
        return result;
    }

    private Inhabitant findParent(final Inhabitant cb) {
        if (cb.parent != null) {
            return cb.parent;
        }
        final String parentName = getParentNameFromByteCode(cb.className);
        if (parentName != null) {
            Inhabitant parent = configBeans.get(parentName);
            if (parent == null) {
                parent = new Inhabitant(parentName);
            }
            configBeans.put(parentName, parent);
            return parent;
        }
        return null;
    }

    private String getParentConfigured(final DescriptorImpl di) {
        String parentConfigured = getFirstIfAny(di.getMetadata(), "ParentConfigured");
        if (parentConfigured == null &&
                (di.getAdvertisedContracts().contains(CONFIG_BEAN_NAME)
                 || di.getAdvertisedContracts().contains(CONFIG_BEAN_PROXY_NAME))) {
            List<String> targets = di.getMetadata().get("target");
            if (targets != null && targets.size() > 0) {
                parentConfigured = getParentNameFromByteCode(targets.get(0));
            }
        }
        return parentConfigured;
    }

    private String getParentNameFromByteCode(final String className) {
        String result = null;
        InputStream is = null;
        try {
            is = loader.getResourceAsStream(className.replace('.', '/') + ".class");
            if (is == null) {
                return null;
            }
            final TypeAnalyzer ta = new TypeAnalyzer(is, knownCommandTypes, this);
            ta.run();
            /*
             * If the bean extends one of the xxxExtension interfaces then
             * make the xxx this bean's parent.
             */
            for (String extensionName : EXTENSION_INTERNAL_NAMES) {
                if (ta.interfaces().contains(extensionName)) {
                    Type t = Type.getObjectType(extensionName);
                    return t.getClassName();
                }
            }
            return result;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    private String getFirstIfAny(final Map<String,List<String>> map, final String key) {
        final List<String> values = map.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private List<Inhabitant> findInhabitantsInModule(final Reader r) throws IOException {

        /*
         * Inhabitants files look like this:
         *
         * [implementation-class-name]
         * ...
         * contract={contract-1-name,constract-2-name,...}
         * ...
         * (blank line)
         * [next-implementation-class-name]
         * ...
         */

        final List<Inhabitant> result = new ArrayList<Inhabitant>();

        final LineNumberReader rdr = new LineNumberReader(r);

        String line;

        String implClassName;

        /*
         * This Inhabitant accumulates information from several successive
         * lines in the hk2 inhabitants file.  Once we detect the end of this
         * inhabitants info - either a blank line or the end of the file -
         * we process this inhabitant.
         */
        Inhabitant inhabitant = null;

        while ((line = rdr.readLine()) != null) {
            final int commentSlot = line.indexOf('#');
            if (commentSlot != -1) {
                line = line.substring(0, commentSlot);
            }
            line = line.trim();
            if (line.isEmpty()) {
                inhabitant = null;
                continue;
            }

            final Matcher implClassNameMatcher = INHABITANT_IMPL_CLASS_PATTERN.matcher(line);
            if (implClassNameMatcher.matches()) {
                implClassName = implClassNameMatcher.group(1);
                getLog().debug("Detected start of inhabitant: " + implClassName);

                inhabitant = new Inhabitant(implClassName);
                result.add(inhabitant);
            } else {
                final Matcher contractsMatcher = INHABITANT_CONTRACTS_PATTERN.matcher(line);
                if (contractsMatcher.matches()) {
                    inhabitant.contracts.addAll(Arrays.asList(contractsMatcher.group(1).split(",")));
                } else {
                    final Matcher nameMatcher = INHABITANT_NAME_PATTERN.matcher(line);
                    if (nameMatcher.matches()) {
                        inhabitant.serviceName = nameMatcher.group(1);
                    } else {
                        if (GENERIC_CRUD_COMMAND_CLASS_NAMES.contains(inhabitant.className)) {
                            final Matcher genericInfoMatcher = GENERIC_COMMAND_INFO_PATTERN.matcher(line);
                            if (genericInfoMatcher.matches()) {
                                inhabitant.methodListActual = genericInfoMatcher.group(1);
                                inhabitant.methodName = genericInfoMatcher.group(2);
                                inhabitant.parentConfigured = genericInfoMatcher.group(3);
                                getLog().debug("Recognized generic command " + inhabitant.serviceName);
                                inhabitant.action = genericCommandNameToAction.get(inhabitant.className);
                                Inhabitant configBeanParent = configBeans.get(inhabitant.parentConfigured);
                                if (configBeanParent == null) {
                                    configBeanParent = new Inhabitant(inhabitant.parentConfigured);
                                    configBeans.put(configBeanParent.className, configBeanParent);
                                    getLog().debug("Created parent bean " + configBeanParent.className + " for target bean " + inhabitant.methodListActual);
                                } else {
                                    getLog().debug("Found parent bean " + configBeanParent.className + " for target bean " + inhabitant.methodListActual);
                                }

                                Inhabitant configBean = configBeans.get(inhabitant.methodListActual);
                                if (configBean == null) {
                                    configBean = new Inhabitant(inhabitant.methodListActual);
                                    configBeans.put(configBean.className, configBean);
                                    getLog().debug("Created new config bean for " + configBean.className);
                                } else {
                                    getLog().debug("Found existing config bean for " + configBean.className);
                                }
                                configBean.parent = configBeanParent;
                                inhabitant.configBeanForCommand = configBean;
                            }
                        }
                    }
                }
            }

            /*
             * If the previously-found contracts include ConfigInjector then
             * this is a config bean.  Try matching the config bean metadata.
             */
            final Matcher configBeanPrefixMatcher = CONFIG_BEAN_METADATA_PREFIX_PATTERN.matcher(line);
            if (configBeanPrefixMatcher.matches()) {
                final String configBeanClassName = configBeanPrefixMatcher.group(1);
                getLog().debug("Recognized " + configBeanClassName + " as a config bean");
                Inhabitant configBean = configBeans.get(configBeanClassName);
                if (configBean == null) {
                    configBean = new Inhabitant(configBeanClassName);
                    configBeans.put(configBeanClassName, configBean);
                }

                /*
                 * If the prefix has a group 2 then that is the part we
                 * need to parse for children.
                 */
                final String restOfLine = configBeanPrefixMatcher.group(2);
                if ( restOfLine != null &&
                    restOfLine.length() > 0) {
                    final Matcher configBeanChildMatcher =
                        CONFIG_BEAN_CHILD_PATTERN.matcher(restOfLine);
                    while (configBeanChildMatcher.find()) {
                        /*
                         * Make sure we have group 1.  Otherwise we matched
                         * one of the variants that we are not interested in
                         * (such as an attribute declaration).
                         */
                        if (configBeanChildMatcher.groupCount() > 0 && configBeanChildMatcher.group(1) != null) {
                            final String childClassName = configBeanChildMatcher.group(2);
                            final String subpathInParent = configBeanChildMatcher.group(1);
                            getLog().debug("Identified " + childClassName + " as child " + subpathInParent + " of " + configBean.className);
                            Inhabitant childInh = configBeans.get(childClassName);
                            if (childInh == null) {
                                childInh = new Inhabitant(childClassName);
                                configBeans.put(childClassName, childInh);
                                getLog().debug("Added child inhabitant to configBeans");
                            } else {
                                getLog().debug("Found child as previously-defined config bean");
                            }
                            getLog().debug("Assigning " + configBean.className + " as parent of " + childInh.className);
                            childInh.parent = configBean;

                            if (configBean.children == null) {
                                configBean.children = new HashMap<String,Child>();
                            }
                            Child child = configBean.children.get(childClassName);
                            if (child == null) {
                                child = new Child(subpathInParent);
                                configBean.children.put(childClassName, child);
                                getLog().debug("Adding config bean " + childClassName + " as child " + subpathInParent + " to config bean " + configBean.className);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private URLClassLoader createClassLoader() throws MojoExecutionException {
        final List<String> compileClasspathElements;
        try {
            compileClasspathElements = project.getRuntimeClasspathElements();
            final URL[] urls = new URL[compileClasspathElements.size()];
            int urlSlot = 0;

            for (String cpElement : compileClasspathElements) {
                getLog().debug(" Processing class path element " + cpElement);
                urls[urlSlot++] = new File(cpElement).toURI().toURL();
            }

            return new URLClassLoader(urls);

        } catch (DependencyResolutionRequiredException ex) {
            throw new MojoExecutionException("Error fetching compile-time classpath", ex);
        } catch (MalformedURLException ex) {
            throw new MojoExecutionException("Error processing class path URL segment", ex);
        }
    }

    private enum GenericCommand {
        CREATE(GENERIC_CREATE_COMMAND, "create"),
        DELETE(GENERIC_DELETE_COMMAND, "delete"),
        LIST(GENERIC_LIST_COMMAND, "read"),
        UNKNOWN("","????");

        private final String commandType;
        private final String action;
        GenericCommand(final String commandType, final String action) {
            this.commandType = commandType;
            this.action = action;
        }

        String action() {
            return action;
        }

        static GenericCommand match(final String commandType) {
            for (GenericCommand gc : GenericCommand.values()) {
                if (gc.commandType.equals(commandType)) {
                    return gc;
                }
            }
            return UNKNOWN;
        }

    }

    public class Inhabitant {

        private List<String> contracts = new ArrayList<>();

        private boolean isFilledIn = false;
        private String className;
        private String serviceName;
        private String methodListActual;
        private String methodName;
        private String parentConfigured;
        private Inhabitant parent = null;
        private String nameInParent = null;
        private String action;
        private Inhabitant configBeanForCommand = null;

        private Map<String,Child> children = null;

        private Inhabitant() {}

        private Inhabitant(final String className) {
            this.className = className;
        }

        private Inhabitant(final String className, final List<String> contracts,
                final String serviceName,
                final String methodListActual,
                final String methodName,
                final String parentConfigured) {
            this.className = className;
            this.contracts.addAll(contracts);
            this.serviceName = serviceName;
            this.methodListActual = methodListActual;
            this.methodName = methodName;
            this.parentConfigured = parentConfigured;
            this.action = GenericCommand.match(className).action;
            this.isFilledIn = true;
        }

        void setParent(final Inhabitant p) {
            parent = p;
            Inhabitant ancestor = this;
            while (ancestor != null) {
                if (p == ancestor) {
                    throw new RuntimeException("Ancestry loop: me=" + toString() + " and candidate parent = " + p.toString());
                }
                ancestor = ancestor.parent;
            }
        }

        void set(final List<String> contracts, final String serviceName,
                final String methodListActual,
                final String methodName,
                final String parentConfigured) {
            this.contracts = contracts;
            this.serviceName = serviceName;
            this.methodListActual = methodListActual;
            this.methodName = methodName;
            this.parentConfigured = parentConfigured;
            isFilledIn = true;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || ! Inhabitant.class.isAssignableFrom(obj.getClass())) {
                return false;
            }
            final Inhabitant other = (Inhabitant) obj;
            return  check(action, other.action) &&
                    check(className, other.className) &&
                    check(configBeanForCommand, other.configBeanForCommand) &&
                    check(contracts, other.contracts) &&
                    (isFilledIn == other.isFilledIn) &&
                    check(methodListActual, other.methodListActual) &&
                    check(methodName, other.methodName) &&
                    check(nameInParent, other.nameInParent) &&
                    check(parentConfigured, other.parentConfigured) &&
                    check(serviceName, other.serviceName)
                    ;

        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 29 * hash + (this.contracts != null ? this.contracts.hashCode() : 0);
            hash = 29 * hash + (this.isFilledIn ? 1 : 0);
            hash = 29 * hash + (this.className != null ? this.className.hashCode() : 0);
            hash = 29 * hash + (this.serviceName != null ? this.serviceName.hashCode() : 0);
            hash = 29 * hash + (this.methodListActual != null ? this.methodListActual.hashCode() : 0);
            hash = 29 * hash + (this.methodName != null ? this.methodName.hashCode() : 0);
            hash = 29 * hash + (this.parentConfigured != null ? this.parentConfigured.hashCode() : 0);
            hash = 29 * hash + (this.nameInParent != null ? this.nameInParent.hashCode() : 0);
            hash = 29 * hash + (this.action != null ? this.action.hashCode() : 0);
            hash = 29 * hash + (this.configBeanForCommand != null ? this.configBeanForCommand.hashCode() : 0);
            return hash;
        }

        private boolean check(final Object x, final Object y) {
            return (x == null? y == null : x.equals(y));
        }

        @Override
        public String toString() {
            return "Inhabitant: " + className + " @Service(\"" + serviceName + "\")\n";
        }

        boolean isFilledIn() {
            return isFilledIn;
        }

        Inhabitant parent() {
            return parent;
        }

        String nameInParent() {
            return nameInParent;
        }

        String fullPath() {
            final StringBuilder path = new StringBuilder();
            for (Inhabitant i = (configBeanForCommand != null ? configBeanForCommand : this); i != null; i = i.parent) {
                if (path.length() > 0 && path.charAt(0) != '/') {
                    path.insert(0, '/');
                }
                final Inhabitant p = i.parent;
                if (p != null) {
                    Child childForThisInh = null;
                    if (p.children != null && ((childForThisInh = p.children.get(i.className)) != null)) {
                        if (childForThisInh.subpathInParent.equals("*") && path.length() > 0) {
                            path.replace(0, 0, "");
                            if (path.substring(0,1).equals("//")) {
                                path.replace(0, 0, "");
                            }
                        } else {
                            path.insert(0, childForThisInh.subpathInParent);
                        }
                    } else {
                        path.insert(0, Util.convertName(Util.lastPart(i.className)));
                    }
                } else {
                    path.insert(0, Util.convertName(Util.lastPart(i.className)));
                }

            }
            return path.toString();
        }
    }

    static class Child {
        private String subpathInParent;

        Child(final String subpathInParent) {
            this.subpathInParent = subpathInParent;
        }
    }
}
