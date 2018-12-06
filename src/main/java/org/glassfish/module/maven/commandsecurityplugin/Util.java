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

import java.util.regex.Pattern;

/**
 *
 * @author tjquinn
 */
public class Util {
    /*
        * Next block stolen shamelessly from the hk2 config Dom class, pending
        * a slight refactoring of that code there to expose the part we need.
        */
    static final Pattern TOKENIZER;
    private static String split(String lookback,String lookahead) {
        return "((?<="+lookback+")(?="+lookahead+"))";
    }
    private static String or(String... tokens) {
        StringBuilder buf = new StringBuilder();
        for (String t : tokens) {
            if(buf.length()>0)  buf.append('|');
            buf.append(t);
        }
        return buf.toString();
    }
    static {
        String pattern = or(
                split("x","X"),     // AbcDef -> Abc|Def
                split("X","Xx"),    // USArmy -> US|Army
                //split("\\D","\\d"), // SSL2 -> SSL|2
                split("\\d","\\D")  // SSL2Connector -> SSL|2|Connector
        );
        pattern = pattern.replace("x","\\p{Lower}").replace("X","\\p{Upper}");
        TOKENIZER = Pattern.compile(pattern);
    }

    static String convertName(final String name) {
        // tokenize by finding 'x|X' and 'X|Xx' then insert '-'.
        StringBuilder buf = new StringBuilder(name.length()+5);
        for(String t : TOKENIZER.split(name)) {
            if(buf.length()>0)  buf.append('-');
            buf.append(t.toLowerCase());
        }
        return buf.toString();  
    }

    /* end of shameless copy */
    
    static String restOpTypeToAction(final String restOpType) {
        if (restOpType.equals("POST")) {
            return "update";
        } else if (restOpType.equals("PUT")) {
            return "update";
        } else if (restOpType.equals("GET")) {
            return "read";
        } else if (restOpType.equals("DELETE")) {
            return "delete";
        } else {
            return "????";
        }
    }

    /**
     *
     * @param s the value of s
     */
    static String lastPart(final String s) {
        final int lastDot = s.lastIndexOf('.');
        return s.substring(lastDot + 1);
    }
}
