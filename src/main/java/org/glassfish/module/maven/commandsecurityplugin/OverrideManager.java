/*
 * Copyright (c) 2013, 2018 Oracle and/or its affiliates. All rights reserved.
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
import java.io.FileReader;
import java.io.LineNumberReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.logging.Log;
import org.glassfish.module.maven.commandsecurityplugin.CommandAuthorizationInfo.ResourceAction;

/**
 *
 * @author tjquinn
 */
public class OverrideManager {
    
    private Map<String, List<ResourceAction>> overrides =
            Collections.EMPTY_MAP;
            
    
    OverrideManager(final File overrideFile, final Log log) {
        overrides = readOverrides(overrideFile, log);
    }
    
    private static Map<String,List<ResourceAction>> readOverrides(final File overrideFile, final Log log)  {
        if ( ! overrideFile.canRead()) {
            log.debug("No readable exceptions file " + overrideFile.getAbsolutePath());
            return Collections.EMPTY_MAP;
        }
        Map<String, List<ResourceAction>> result = 
                new HashMap<String,List<ResourceAction>>();
        try {
            final LineNumberReader reader = new LineNumberReader(new FileReader(overrideFile));
            String line = "";
            try {
                while ((line = reader.readLine()) != null) {
                    final List<ResourceAction> resourceActionPairs =
                            new ArrayList<ResourceAction>();
                    final String[] parts = line.split("\\|");
                    final String name = parts[0].trim();
                    final String[] overriddenResourceActionPairs = parts[1].trim().split(",");
                    for (String pair : overriddenResourceActionPairs) {
                        final String[] resourceAction = pair.split(":");
                        resourceActionPairs.add(new ResourceAction(resourceAction[0].trim(), resourceAction[1].trim(), resourceAction[2].trim()));
                    }
                    result.put(name, resourceActionPairs);
                }
            } catch (Exception ex) {
                log.warn("Error processing exceptions file containing line " + line + " ; ignoring and continuing", ex);
            } finally {
                reader.close();
            }
        } catch (Exception ex) {
            
            result = Collections.EMPTY_MAP;
        }
        return result;
    }
    
    CommandAuthorizationInfo adjust(final CommandAuthorizationInfo info) {
        if (overrides.containsKey(info.name())) {
            info.overrideResourceActions(overrides.get(info.name()));
        }
        return info;
    }
}
