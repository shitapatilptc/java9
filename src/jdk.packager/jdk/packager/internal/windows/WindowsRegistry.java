/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package jdk.packager.internal.windows;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static com.oracle.tools.packager.IOUtils.exec;

public final class WindowsRegistry {

    private WindowsRegistry() {}

    /**
     * Reads the registry value for DisableRealtimeMonitoring.
     * @return true if DisableRealtimeMonitoring is set to 0x1, false otherwise.
     */
    public static final boolean readDisableRealtimeMonitoring() {
        boolean result = false;
        final String key = "HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows Defender\\Real-Time Protection";
        final String subkey = "DisableRealtimeMonitoring";
        String value = readRegistry(key, subkey);

        if (!value.isEmpty()) {
            // This code could be written better but this works. It validates
            // that the result of readRegistry returned what we expect and then
            // checks for a 0x0 or 0x1. 0x0 means real time monitoring is
            // on, 0x1 means it is off. So this function returns true if
            // real-time-monitoring is disabled.
            int index = value.indexOf(subkey);
            value = value.substring(index + subkey.length());
            String reg = "REG_DWORD";
            index = value.indexOf(reg);
            value = value.substring(index + reg.length());
            String hex = "0x";
            index = value.indexOf(hex);
            value = value.substring(index + hex.length());

            if (value.equals("1")) {
                result = true;
            }
        }

        return result;
    }

    public static final List<String> readExclusionsPaths() {
        List<String> result = new ArrayList();
        final String key = "HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows Defender\\Exclusions\\Paths";
        String value = readRegistry(key, "");

        if (!value.isEmpty()) {
            final String reg = "REG_DWORD";
            final String hex = "0x0";

            int index = value.indexOf(key);
            if (index == 0) {
                value = value.substring(index + key.length());

                while (value.length() > 0) {
                    index = value.indexOf(reg);
                    String name = value.substring(0, index);
                    value = value.substring(index + reg.length());
                    index = value.indexOf(hex);
                    value = value.substring(index + hex.length());

                    if (index > 0) {
                        name = name.trim();
                        result.add(name);
                    }
                }
            }
        }

        return result;
    }
    /**
     * @param key in the registry
     * @param subkey in the registry key
     * @return registry value or null if not found
     */
    public static final String readRegistry(String key, String subkey){
        String result = "";

        try {
            List<String> buildOptions = new ArrayList<>();
            buildOptions.add("reg");
            buildOptions.add("query");
            buildOptions.add("\"" + key + "\"");

            if (!subkey.isEmpty()) {
                buildOptions.add("/v");
                buildOptions.add(subkey);
            }

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); PrintStream ps = new PrintStream(baos)) {
                ProcessBuilder security = new ProcessBuilder(buildOptions);
                exec(security, false, false, ps);
                BufferedReader bfReader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(baos.toByteArray())));
                String line = null;

                while((line = bfReader.readLine()) != null){
                    result += line;
                }
            }
            catch (IOException e) {
            }
        }
        catch (Exception e) {
        }

        return result;
    }
}
