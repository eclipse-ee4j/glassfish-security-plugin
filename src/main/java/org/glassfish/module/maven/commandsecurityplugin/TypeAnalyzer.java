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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.objectweb.asm.*;

/**
 * Analyzes a class, searching for annotations or interface implementations
 * that mean the class handles authorization.
 * 
 * @author tjquinn
 */
public class TypeAnalyzer {
    
    private final InputStream classStream;
    private final boolean isOpenedAsFile;
    
    private final static String ACCESS_REQUIRED_DESC_PATH_ONLY = "org/glassfish/api/admin/AccessRequired";
    private final static String ACCESS_REQUIRED_DESC = 'L' + ACCESS_REQUIRED_DESC_PATH_ONLY + ';';
    private final static String ACCESS_REQUIRED_LIST_DESC = 'L' + ACCESS_REQUIRED_DESC_PATH_ONLY + "$List;";
    
    private final static String ACCESS_REQUIRED_DELEGATE_PATH_ONLY = "org/glassfish/api/admin/AccessRequired$Delegate";
    private final static String ACCESS_REQUIRED_DELEGATE_DESC = 'L' + ACCESS_REQUIRED_DELEGATE_PATH_ONLY + ';';
    
    private final static String REST_ENDPOINT_DESC_PATH_ONLY = "org/glassfish/api/admin/RestEndpoint";
    private final static String REST_ENDPOINT_DESC = 'L' + REST_ENDPOINT_DESC_PATH_ONLY + ';';
    private final static String REST_ENDPOINTS_DESC = 'L' + REST_ENDPOINT_DESC_PATH_ONLY + "s;";
    
    private static final String SERVICE_ANNO_DESC = "Lorg/jvnet/hk2/annotations/Service;";
    private static final String SUPPLEMENTAL_ANNO_DESC = "Lorg/glassfish/api/admin/Supplemental;";
    
    private static final String CONFIGURED_ANNO_DESC = "Lorg/jvnet/hk2/config/Configured;";
        
    private final static String ADMIN_COMMAND_INTERNAL_NAME = "org/glassfish/api/admin/AdminCommand"; // interface
    private final static String CLI_COMMAND_INTERNAL_NAME = "com/sun/enterprise/admin/cli/CLICommand";  // class
    
    private final static String DOMAIN_EXTENSION_INTERNAL_NAME = "com/sun/enterprise/config/serverbeans/DomainExtension";
    private final static String DOMAIN_INTERNAL_NAME = "com/sun/enterprise/config/serverbeans/Domain";
    
    private final static String LINE_SEP = System.getProperty("line.separator");
    
//    private static final Collection<String> COMMAND_BASE_INTERFACE_NAMES = 
//                new HashSet<String>(Arrays.asList("org/glassfish/api/admin/AdminCommand",
//            "com/sun/enterprise/admin/cli/CLICommand"));
    
    private static final Collection<String> AUTHORIZATION_RELATED_INTERFACES =
            new HashSet<String>(Arrays.asList("org/glassfish/api/admin/AdminCommandSecurity$AccessCheckProvider"));
        
    private StringBuilder trace = null;
    
    private CommandAuthorizationInfo commandAuthInfo = null;
    private boolean isCommand = false;
    
    private final Map<String,CommandAuthorizationInfo> knownCommandTypes;
    private final TypeProcessor typeProcessor;
    
    private ServiceAnnotationScanner service;
    private SupplementalAnnotationScanner supplemental;
   
    private CommandScanner cs;
        
    
//    TypeAnalyzer(final File classFile, final TypeProcessor typeProcessor) throws FileNotFoundException {
//        this(new BufferedInputStream(new FileInputStream(classFile)), true,
//                typeProcessor);
//    }
    
    TypeAnalyzer(final InputStream classStream, final Map<String,CommandAuthorizationInfo> knownCommandTypes, final TypeProcessor typeProcessor) {
        this(classStream, false, knownCommandTypes, typeProcessor);
    }
    
    void setTrace(final StringBuilder sb) {
        trace = sb;
    }
    
    private TypeAnalyzer(final InputStream classStream, final boolean isOpenedAsFile,
            final Map<String,CommandAuthorizationInfo> knownCommandTypes, 
            final TypeProcessor typeProcessor) {
        this.classStream = classStream;
        this.isOpenedAsFile = isOpenedAsFile;
        this.knownCommandTypes = knownCommandTypes;
        this.typeProcessor = typeProcessor;
    }
    
    void run() throws FileNotFoundException, IOException {
        try {
            final ClassReader classReader = new ClassReader(classStream);
            cs = new CommandScanner();
            classReader.accept(cs, ClassReader.SKIP_CODE + ClassReader.SKIP_DEBUG + ClassReader.SKIP_FRAMES);
            isCommand = cs.isCommand();
//            if (cs.isCommand()) {
                commandAuthInfo = cs.commandInfo();
//            }
            
        } finally {
            if (isOpenedAsFile) {
                classStream.close();
            }
        }
    }
    
    List<String> interfaces() {
        return (cs == null ? Collections.EMPTY_LIST : cs.interfaces);
    }
    
    /**
     * Returns the command auth information for the class analyzed by this
     * instance.
     * 
     * @return command auth info if the analyzed class is a command; null otherwise
     */
    CommandAuthorizationInfo commandAuthInfo() {
        return commandAuthInfo;
    }
    
    boolean isCommand() {
        return isCommand;
    }
    
    /**
     * Analyzes a class as ASM invokes the various methods during
     * a scan of the class's byte code.  After the class (and its ancestors
     * if needed) have been analyzed, the commandAuthInfo will be non-null
     * if the class is a command and null if it is not a command.
     */
    private class CommandScanner extends ClassVisitor {
    
        private CommandAuthorizationInfo commandAuthInfo = null;
    
        private boolean isCommand = false;
        
        private String superName;
        private String className;
        
        private List<String> interfaces;
        
        CommandScanner() {
            super(Opcodes.ASM5);
        }

        boolean isCommand() {
            return isCommand;
        }
        
        CommandAuthorizationInfo commandInfo() {
            return commandAuthInfo;
        }
        
        List<String> interfaces() {
            return interfaces;
        }
        
        @Override
        public void visit(int version,
         int access,
         String name,
         String signature,
         String superName,
         String[] interfaces) {
            /*
             * We won't get here unless this class appeared as a command
             * in the inhabitants file or is extended by such
             * an inhabitant and also is not yet accounted for in the
             * type catalog that records which types are known commands and
             * which are known non-commands.
             */
            className = name;
            if (trace != null) {
                trace.append(LINE_SEP).append("  Starting to analyze class ").append(name);
            }
            this.superName = superName;
            this.interfaces = new ArrayList<String>(Arrays.asList(interfaces));
            isCommand = isExtensionOrImplementationOfCommandType(name, interfaces);
            
            commandAuthInfo = new CommandAuthorizationInfo();
            commandAuthInfo.setClassName(className);
            
            
            checkForAuthRelatedInterfaces(interfaces);
        }
        
        private void checkForAuthRelatedInterfaces(final String[] interfaces) {
            for (String iface : interfaces) {
                if (AUTHORIZATION_RELATED_INTERFACES.contains(iface)) {
                    commandAuthInfo.isAccessCheckProvider.set(true);
                    return;
                }
            }
        }
        
//        
//        private void checkForCommandAncestor(final String className,
//                final String superName) {
//        }
        
        private boolean isExtensionOrImplementationOfCommandType(final String name, final String[] interfaces) {
            boolean result = false;
            for (String i : interfaces) {
                if ((result = i.equals(ADMIN_COMMAND_INTERNAL_NAME))) {
                    break;
                }
            }
            return result;
        }
        
        @Override
        public FieldVisitor visitField(int access,
                      String name,
                      String desc,
                      String signature,
                      Object value) {
            
//            if (! commandAuthInfo.isOK()) {
                final FieldScanner f = new FieldScanner(commandAuthInfo, name, desc);
                return f;
//            } else {
//                return null;
//            }
        }
        
        @Override
        public AnnotationVisitor visitAnnotation(String desc,
                                boolean visible) {
            
//            if (! commandAuthInfo.isOK()) {
                if (desc.equals(ACCESS_REQUIRED_DESC)) {
                    if (trace != null) {
                        trace.append(LINE_SEP).append("  Found @AccessRequired at class level");
                    }
                    commandAuthInfo.hasCommandLevelAccessRequiredAnno.set(true);
                    return new CommandLevelAccessRequiredAnnotationScanner(commandAuthInfo);
                } else if (desc.equals(ACCESS_REQUIRED_DELEGATE_DESC)) {
                    if (trace != null) {
                        trace.append(LINE_SEP).append("  Found @AccessRequired.Delegate at class level");
                    }
                    commandAuthInfo.hasCommandLevelAccessRequiredAnno.set(true);
                    return new CommandLevelAccessRequiredDelegateAnnotationScanner(commandAuthInfo);
                } else if (desc.equals(REST_ENDPOINT_DESC)) {
                    if (trace != null) {
                        trace.append(LINE_SEP).append("  Found @RestEndpoint at class level");
                    }
//                    commandAuthInfo.hasRestAnno.set(true);
                    return new RestEndpointAnnoScanner(commandAuthInfo);
                } else if (desc.equals(ACCESS_REQUIRED_LIST_DESC)) {
//                        return new AccessRequiredListAnnoScanner(commandAuthInfo);
                        return new RepeatingAnnoScanner(commandAuthInfo, 
                                ACCESS_REQUIRED_DESC,
                                CommandLevelAccessRequiredAnnotationScanner.class);
                } else if (desc.equals(REST_ENDPOINTS_DESC)) {
                    return new RestEndpointsAnnoScanner(commandAuthInfo);
//                    return new RepeatingAnnoScanner(commandAuthInfo.hasRestAnno, REST_ENDPOINT_DESC);
                } else if (desc.equals(SERVICE_ANNO_DESC)) {
                    return new ServiceAnnotationScanner(commandAuthInfo);
                } else if (desc.equals(SUPPLEMENTAL_ANNO_DESC)) {
                    return new SupplementalAnnotationScanner();
                } else {
                    return null;
                }
//            } 
        }

        @Override
        public void visitEnd() {
           /*
            * If we know this is a command and this class also has authorization-
            * related annos or implements auth-related interfaces, then we
            * are satisfied; there is no need to check the class's ancestors.
            */
            if (isCommand) {
                if (commandAuthInfo.isOK()) {
                    if (trace != null) {
                        trace.append(LINE_SEP).append("  Recognized that ").append(className).append(" is a command and has authorization without checking ancestors");
                    }
//                    return;
                } else {
                    if (trace != null) {
                        trace.append(LINE_SEP).append("  Recognized that ").append(className).append(" is a command but itself has no authorization");
                    }
                }
            } else {
                if (trace != null) {
                    trace.append(LINE_SEP).append("  Recognized that ").append(className).append(" is not itself a command; an ancestor might be");
                }
            }

           /*
            * Process the class's ancestors to gather any inherited @Params or
            * @RestEndpoints.
            */
            try {
                if (superName != null) {
                    final CommandAuthorizationInfo parentInfo = typeProcessor.processType(superName);
                    boolean isParentCommand = knownCommandTypes.containsKey(superName);
                    isCommand |= isParentCommand;
                    if (isParentCommand) {
                        if (trace != null) {
                            trace.append(LINE_SEP).
                                    append("  Detected that ").
                                    append(className).
                                    append(" is a command based on its ancestry; check of parent and its ancestry for auth: ").
                                    append(parentInfo.isOKDeep());
                        }
                    
                    } else if ( ! isCommand) {
                        if (trace != null) {
                            trace.append(LINE_SEP).append("  Detected that ").
                                    append(className).
                                    append(" is not a command, even including its ancestry");
                        }
                    }
                    commandAuthInfo.setParent(parentInfo);
                }
                
                if (isCommand && service != null) {
                    if (supplemental != null) {
                    commandAuthInfo.setName(supplemental.relation());
                } else {
                    commandAuthInfo.setName(service.name());
                }
                commandAuthInfo.setClassName(className);
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        
        
    }
    
//    private class AccessRequiredListAnnoScanner extends AnnotationVisitor {
//        
//        private final CommandAuthorizationInfo authInfo;
//        
//        private AccessRequiredListAnnoScanner(final CommandAuthorizationInfo authInfo) {
//            super(Opcodes.ASM5);
//            this.authInfo = authInfo;
//        }
//
//        @Override
//        public AnnotationVisitor visitAnnotation(String name, String desc) {
//            System.err.println("*** AccessRequiredListAnnoScanner.visitAnno saw name=" + name + " , desc=" + desc);
//            if (name.equals(ACCESS_REQUIRED_DESC)) {
//                if (trace != null) {
//                    trace.append(LINE_SEP).append("    Found ").append(name).append(" in array at class level");
//                }
//                authInfo.hasCommandLevelAccessRequiredAnno.set(true);
//            }
//            return null;
//        }
//
//        @Override
//        public AnnotationVisitor visitArray(String name) {
//            return new AnnoScanner(authInfo.hasCommandLevelAccessRequiredAnno, ACCESS_REQUIRED_DESC);
//        }
//    }
//    
    private static class CommandLevelAccessRequiredAnnotationScanner extends AnnotationVisitor {
        private final List<String> resources = new ArrayList<String>();
        private final List<String> actions = new ArrayList<String>();
        private final CommandAuthorizationInfo commandAuthInfo;
        
        public CommandLevelAccessRequiredAnnotationScanner(
                final CommandAuthorizationInfo commandAuthInfo) {
            super(Opcodes.ASM5);
            this.commandAuthInfo = commandAuthInfo;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String desc) {
            /*
             * Invoked only when the command has @AccessRequired.List and 
             * the repeating anno processor is processing one of the list 
             * items.
             */
            return new CommandLevelAccessRequiredAnnotationScanner(commandAuthInfo);
        }
        
        @Override
        public AnnotationVisitor visitArray(String name) {
            if (name.equals("resource")) {
                return new MultiValuedAnnoVisitor(resources);
            } else if (name.equals("action")) {
                return new MultiValuedAnnoVisitor(actions);
            } 
            return super.visitArray(name);
        }

        @Override
        public void visitEnd() {
            if ( ! resources.isEmpty() && ! actions.isEmpty()) {
                commandAuthInfo.hasCommandLevelAccessRequiredAnno.set(true);
            }
            for (String resource : resources) {
                for (String action : actions) {
                    commandAuthInfo.addResourceAction(resource, action, "@AccessRequired");
                }
            }
            super.visitEnd();
        }
    }
    
    private class CommandLevelAccessRequiredDelegateAnnotationScanner extends AnnotationVisitor {
        
        private final CommandAuthorizationInfo commandAuthInfo;
        
        private CommandLevelAccessRequiredDelegateAnnotationScanner(
                final CommandAuthorizationInfo commandAuthInfo) {
            super(Opcodes.ASM5);
            this.commandAuthInfo = commandAuthInfo;
        }

        @Override
        public void visit(String name, Object value) {
            if (name.equals("value")) {
                commandAuthInfo.setDelegate(((Type) value).getInternalName());
            } else {
                super.visit(name, value);
            }
        }
    }
    
    private class RestEndpointsAnnoScanner extends AnnotationVisitor {
        
        private final CommandAuthorizationInfo authInfo;
        
        private RestEndpointsAnnoScanner(final CommandAuthorizationInfo authInfo) {
            super(Opcodes.ASM5);
            this.authInfo = authInfo;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String desc) {
            if (desc.equals(REST_ENDPOINT_DESC)) {
                if (trace != null) {
                    trace.append(LINE_SEP).append("    Found ").append(name).append(" in array at class level");
                }
                return new RestEndpointAnnoScanner(authInfo);
            }
            return null;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            return new RestEndpointsAnnoScanner(authInfo);
        }
    }
    
    private class RestEndpointAnnoScanner extends AnnotationVisitor {
        
        private final CommandAuthorizationInfo authInfo;
        private String configBeanClassName = null;
        private String path = null;
        private String opType = null;
        private boolean useForAuthorization = false;
        
        private RestEndpointInfo info;
        
        private RestEndpointAnnoScanner(final CommandAuthorizationInfo authInfo) {
            super(Opcodes.ASM5);
            this.authInfo = authInfo;
        }

        @Override
        public void visit(String name, Object value) {
            if (name.equals("configBean")) {
                configBeanClassName = ((Type) value).getInternalName();
            } else if (name.equals("path")) {
                path = (String) value;
            } else if (name.equals("opType")) {
                opType = ((Type) value).getInternalName();
            } else if (name.equals("useForAuthorization")) {
                useForAuthorization = (Boolean)value;
            }
            super.visit(name, value);
        }

        @Override
        public void visitEnum(String name, String desc, String value) {
            if (name.equals("opType")) {
                opType = value;
            }
        }
        
        

        @Override
        public void visitEnd() {
            info = new RestEndpointInfo(configBeanClassName, path, opType, useForAuthorization);
            authInfo.addRestEndpoint(info);
            if (useForAuthorization) {
                authInfo.hasRestAnno.set(true);
            }
            super.visitEnd();
        }
        
        private RestEndpointInfo restEndpointInfo() {
            return info;
        }
        
        
        
        
    }
    
    
    private class RepeatingAnnoScanner extends AnnotationVisitor {
        private final CommandAuthorizationInfo authInfo;
        private final String singleAnnoDesc;
        private final Class<? extends AnnotationVisitor> scannerClass;
        
        private RepeatingAnnoScanner(final CommandAuthorizationInfo authInfo, 
                final String singleAnnoDesc,
                final Class<? extends AnnotationVisitor> scannerClass) {
            super(Opcodes.ASM5);
            this.authInfo = authInfo;
            this.singleAnnoDesc = singleAnnoDesc;
            this.scannerClass = scannerClass;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String desc) {
            if (name.equals(singleAnnoDesc)) {
                if (trace != null) {
                    trace.append(LINE_SEP).append("    Found ").append(name).append(" in array of ").append(singleAnnoDesc).append(" at class level");
                }
            }
            return null;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            try {
                Constructor<? extends AnnotationVisitor> c = scannerClass.getConstructor(CommandAuthorizationInfo.class);
                final AnnotationVisitor v = c.newInstance(authInfo);
                return v;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }
    
    private class AnnoScanner extends AnnotationVisitor {
        
        private final CommandAuthorizationInfo authInfo;
        private final String descToProcess;
        
        private AnnoScanner(final CommandAuthorizationInfo authInfo, final String descToProcess) {
            super(Opcodes.ASM5);
            this.authInfo = authInfo;
            this.descToProcess = descToProcess;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String desc) {
            if (desc.equals(descToProcess)) {
                if (trace != null) {
                    trace.append(LINE_SEP).append("    Found anno name=").append(name).append(", desc=").append(desc);
                }
                authInfo.hasCommandLevelAccessRequiredAnno.set(true);
            }
            return null;
        }
    }
    
    private class FieldScanner extends FieldVisitor {
    
        private final String ACCESS_REQUIRED_TO_DESC = 'L' + ACCESS_REQUIRED_DESC_PATH_ONLY + "$To;";
        private final String ACCESS_REQUIRED_NEW_CHILD_DESC = 'L' + ACCESS_REQUIRED_DESC_PATH_ONLY + "$NewChild;";
        private final Collection<String> FIELD_LEVEL_ANNOS = Arrays.asList(
                ACCESS_REQUIRED_TO_DESC,
                ACCESS_REQUIRED_NEW_CHILD_DESC);
    
        private final CommandAuthorizationInfo commandAuthInfo;
        private final static String PARAM_ANNO_DESC = "Lorg/glassfish/api/Param;" ;// Lorg/glassfish/api/admin/AdminCommand;";
        private final static String STRING_DESC = "Ljava/lang/String;";
    
        private final String name;
        private final String desc;
        private final String friendlyTypeName;
        private CommandAuthorizationInfo.Param param = null;
        
        
        FieldScanner(final CommandAuthorizationInfo commandAuthInfo, final String name, final String desc) {
            super(Opcodes.ASM5);
            this.commandAuthInfo = commandAuthInfo;
            this.name = name;
            this.desc = desc;
            this.friendlyTypeName = friendlyTypeName(desc);
        }
        
        String fullFriendlyTypeName() {
            if (desc.startsWith("L")) {
                return desc.substring(1, desc.length() - 1);
            }
            return desc;
        }
        
        private String friendlyTypeName(final String desc) {
            if (desc.startsWith("L")) {
                if (desc.equals(STRING_DESC)) {
                    return "";
                } else {
                    return desc.substring(desc.lastIndexOf('/') + 1,desc.length()-1);
                }
            }
            return desc;
        }
        
        @Override
        public AnnotationVisitor visitAnnotation(String desc,
                                boolean visible) {
//            System.err.println("  FieldScanner.visitAnno for anno " + desc);
            if (FIELD_LEVEL_ANNOS.contains(desc)) {
                if (trace != null)  {
                    trace.append(LINE_SEP).append("    Found anno ").append(desc);
                }
                commandAuthInfo.hasFieldLevelAccessRequiredAnno.set(true);
            }
            if (desc.equals(PARAM_ANNO_DESC)) {
                param = new CommandAuthorizationInfo.Param(name, friendlyTypeName);
                return new ParamAnnotationScanner(param);
            } else if (desc.equals(ACCESS_REQUIRED_TO_DESC)) {
                return new AccessRequiredToAnnotationScanner(this, commandAuthInfo, param);
            } else if (desc.equals(ACCESS_REQUIRED_NEW_CHILD_DESC)) {
                return new AccessRequiredNewChildAnnotationScanner(this, commandAuthInfo, param);
            } else {
                return null; //super.visitAnnotation(desc, visible);
            }
        }

        @Override
        public void visitEnd() {
            if (param != null) {
                final Object nameValue = param.values().get("name");
                if (nameValue != null && nameValue instanceof String) {
                    param.setName((String) nameValue);
                }
                commandAuthInfo.addParam(param);
            }
            super.visitEnd();
        }
        
        
    }
    
    private static class ServiceAnnotationScanner extends AnnotationVisitor {
        private String name = null;
        private final CommandAuthorizationInfo authInfo;
        
        ServiceAnnotationScanner(final CommandAuthorizationInfo authInfo) {
            super(Opcodes.ASM5);
            this.authInfo = authInfo;
        }
        
        @Override
        public void visit(String name,
         Object value) {
            if (name.equals("name")) {
                if (value instanceof String) {
                    this.name = (String) value;
                    authInfo.setName(this.name);
                } else {
                    System.err.println("** @Service name value is not a String");
                }
            }
        }
        
        String name() {
            return name;
        }
    }
    
    private static class SupplementalAnnotationScanner extends AnnotationVisitor {

        private static final String SUPPLEMENTAL_TIMING_DESC = "Lorg/glassfish/api/admin/Supplemental$Timing;";
        private static final String BEFORE = "Before";
        private static final String AFTER = "After";
        private static final String AFTER_REPLICATION = "AfterReplication";
        
        private String relatedCommand;
        private boolean isBefore = false;
        private boolean isAfter = false;
        private boolean isAfterReplication = false;
        
        public SupplementalAnnotationScanner() {
            super(Opcodes.ASM5);
        }
        
        @Override
        public void visit(String name,
         Object value) {
            if (name.equals("value")) {
                if (value instanceof String) {
                    this.relatedCommand = (String) value;
                } else {
                    System.err.println("** @Supplemental 'value' value is not a String");
                }
            }
        }
        
        @Override
        public void visitEnum(String name,
             String desc,
             String value) {
            if (name.equals("on") && desc.equals(SUPPLEMENTAL_TIMING_DESC)) {
                isBefore |= value.equals(BEFORE);
                isAfter |= value.equals(AFTER);
                isAfterReplication |= value.equals(AFTER_REPLICATION);
            }
        }
        
        String relatedCommand() {
            return relatedCommand;
        }
        
        String relation() {
            return "[" + (isBefore ? "+" : "") + relatedCommand + (isAfter ? "+" : (isAfterReplication ? "++" : "")) + "]";
        }
        
    }
    
    private static class FieldScannerForOutput extends FieldVisitor {
    
        private final static String PARAM_ANNO_DESC = "Lorg/glassfish/api/Param;" ;// Lorg/glassfish/api/admin/AdminCommand;";
        private final static String STRING_DESC = "Ljava/lang/String;";
    
        private final String name;
        private final String desc;
        private final String friendlyTypeName;
        private final CommandAuthorizationInfo commandAuthInfo;
        
        private CommandAuthorizationInfo.Param param = null; // remains null if the field is not annotated with @Param
        
        
        FieldScannerForOutput(final CommandAuthorizationInfo commandInfo, final String name, final String desc) {
            super(Opcodes.ASM5);
            this.commandAuthInfo = commandInfo;
            this.name = name;
            this.desc = desc;
            if (desc.startsWith("L")) {
                if (desc.equals(STRING_DESC)) {
                    friendlyTypeName = "";
                } else {
                    friendlyTypeName = desc.substring(desc.lastIndexOf('/') + 1,desc.length()-1);
                }
            } else {
                friendlyTypeName = desc;
            }
        }
        
        @Override
        public AnnotationVisitor visitAnnotation(String desc,
                                boolean visible) {
//            System.err.println("  FieldScanner.visitAnno for anno " + desc);
            if (desc.equals(PARAM_ANNO_DESC)) {
                
                param = new CommandAuthorizationInfo.Param(name, friendlyTypeName); // default name and type based on field name and type
                return new ParamAnnotationScanner(param);
            } else {
            return null;
            }
        }

        @Override
        public void visitEnd() {
            if (param != null) {
                final Object nameValue = param.values().get("name");
                if (nameValue != null && nameValue instanceof String) {
                    param.setName((String) nameValue);
                }
                commandAuthInfo.addParam(param);
            }
            super.visitEnd();
        }
        
        
        
    }
    
    private static class ParamAnnotationScanner extends AnnotationVisitor {
        
        private final CommandAuthorizationInfo.Param param;
        
        ParamAnnotationScanner(final CommandAuthorizationInfo.Param p) {
            super(Opcodes.ASM5);
            this.param = p;
        }
        
        @Override
        public void visit(String name,
         Object value) {
//            System.err.println("    AnnotationScanner.visit for value " + name + " = " + value);
            /*
             * Invoked once for each value in the anno.
             */
            if (name.equals("name")) {
                if (value instanceof String) {
                    param.setName((String) value);
                } else {
                    System.err.println("@Param name value is not a String but is " + value.getClass().getName());
                }
            }
            param.addValue(name, value);
            super.visit(name, value);
        }
    }
    
    private class AccessRequiredToAnnotationScanner extends AnnotationVisitor {
        private final FieldScanner fieldScanner;
        private final CommandAuthorizationInfo authInfo;
        private final CommandAuthorizationInfo.Param param;
        private final List<String> actions = new ArrayList<String>();
        private String collection = "";
        
        AccessRequiredToAnnotationScanner(
                final FieldScanner fieldScanner, 
                final CommandAuthorizationInfo authInfo, 
                final CommandAuthorizationInfo.Param param) {
            super(Opcodes.ASM5);
            this.fieldScanner = fieldScanner;
            this.authInfo = authInfo;
            this.param = param;
        }

        @Override
        public void visit(String name, Object value) {
            if (name.equals("value")) {
                if (value instanceof String) {
                    actions.add((String) value);
                } else {
                    actions.addAll(Arrays.asList((String[]) value));
                }
            } else if (name.equals("collection")) {
                collection = (String) value;
            }
            super.visit(name, value);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if (name.equals("value")) {
                return new MultiValuedAnnoVisitor(actions);
            } else {
                return null;
            }
        }
        
        

        @Override
        public void visitEnd() {
            final String dottedTypeName = fieldScanner.fullFriendlyTypeName().replace("/", ".");
            TypeProcessorImpl.Inhabitant i = typeProcessor.configBeans().get(dottedTypeName);
            for (String action : actions) {
                authInfo.addResourceAction(i.fullPath() + (! collection.isEmpty() ? "/" : "") + collection + "/$xxx", action, "@AccessRequired.To");
            }
            super.visitEnd();
        }
    }
    
    private static class MultiValuedAnnoVisitor extends AnnotationVisitor {
        
        private final Collection<String> values;
        
        MultiValuedAnnoVisitor(final Collection<String> values) {
            super(Opcodes.ASM5);
            this.values = values;
        }

        @Override
        public void visit(String name, Object value) {
            values.add((String) value);
            super.visit(name, value);
        }
    }
    
    
    private class AccessRequiredNewChildAnnotationScanner extends AnnotationVisitor {
        private final FieldScanner fieldScanner;
        private final CommandAuthorizationInfo authInfo;
        private final CommandAuthorizationInfo.Param param;
        private final List<String> actions = new ArrayList<String>();
        private String collection = "";
        private String type = "";
        
        AccessRequiredNewChildAnnotationScanner(
                final FieldScanner fieldScanner,
                final CommandAuthorizationInfo authInfo,
                final CommandAuthorizationInfo.Param param) {
            super(Opcodes.ASM5);
            this.fieldScanner = fieldScanner;
            this.authInfo = authInfo;
            this.param = param;
        }

        @Override
        public void visit(String name, Object value) {
            if (name.equals("type")) {
                final String typeName = ((Type) value).getClassName();
                type = typeName;
            } else if (name.equals("collection")) {
                collection = (String) value;
            } else if (name.equals("action")) {
                if (value instanceof String) {
                    actions.add((String) value);
                } else {
                    actions.addAll(Arrays.asList((String[]) value));
                }
            }
            super.visit(name, value);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if (name.equals("action")) {
                return new MultiValuedAnnoVisitor(actions);
            } else {
                return null;
            }
        }
        
        @Override
        public void visitEnd() {
            final String dottedTypeName = fieldScanner.fullFriendlyTypeName().replace("/", ".");
            TypeProcessorImpl.Inhabitant i = typeProcessor.configBeans().get(dottedTypeName);
            if (i == null) {
                throw new IllegalArgumentException("Could not find configBean for " + dottedTypeName);
            }
            if (actions.isEmpty()) {
                actions.add("create");
            }
            for (String action : actions) {
                
                authInfo.addResourceAction(i.fullPath()
                        + ( ! type.isEmpty() ? "/" : "") + type
                        + ( ! collection.isEmpty() ? "/" : "") + collection, action, "@AccessRequired.NewChild");
            }
            super.visitEnd();
        }
    }
}
