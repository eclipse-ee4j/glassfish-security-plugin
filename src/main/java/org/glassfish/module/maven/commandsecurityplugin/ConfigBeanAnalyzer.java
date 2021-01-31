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

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Analyzes a ConfigBean class to see if it has CRUD annotations and, if so,
 * collects the command authorization information about it.
 * 
 * @author tjquinn
 */
public class ConfigBeanAnalyzer  {
    
    private final InputStream classStream;
    private StringBuilder trace = null;
    private CommandAuthorizationInfo commandAuthInfo = null;
    private boolean isCommand = false;
    private final TypeProcessor typeProcessor;
    
    ConfigBeanAnalyzer(final InputStream classStream, final TypeProcessor typeProcessor) {
        this.typeProcessor = typeProcessor;
        this.classStream = classStream;
    }
    
    void setTrace(final StringBuilder trace) {
        this.trace = trace;
    }
    
    void run() throws IOException {
        final ClassReader classReader = new ClassReader(classStream);
        final ConfigBeanScanner cs = new ConfigBeanScanner();
        classReader.accept(cs, ClassReader.SKIP_CODE + ClassReader.SKIP_DEBUG + ClassReader.SKIP_FRAMES);
        isCommand = cs.isCommand();
//        if (cs.isCommand()) {
//            commandAuthInfo = cs.commandInfo();
//        }
    }
    
    CommandAuthorizationInfo commandAuthInfo() {
        return commandAuthInfo;
    }
    
    boolean isCommand() {
        return isCommand;
    }

    /**
     * Scans the config bean class looking for CRUD annotations on the class
     * or on methods, creating a Command
     */
    private class ConfigBeanScanner extends ClassVisitor {
        
        private boolean isCommand = false;
        private Collection<CommandAuthorizationInfo> commandInfos = Collections.EMPTY_LIST;
        
        private ConfigBeanScanner() {
            super(Opcodes.ASM7);
        }
        
        private boolean isCommand() {
            return isCommand;
        }
        
        private Collection<CommandAuthorizationInfo> commandInfos() {
            return commandInfos;
        }
        
        @Override
        public void visit(int version,
            int access,
            String name,
            String signature,
            String superName,
            String[] interfaces) {
            
        }
    }
    
    
    
    
}
