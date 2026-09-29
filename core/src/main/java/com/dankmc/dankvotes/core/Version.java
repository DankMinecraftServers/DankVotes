package com.dankmc.dankvotes.core;

import java.io.InputStream;
import java.util.Properties;

/** Reads the build version that Maven filters into dankvotes.properties. */
public final class Version {
    private static String cached;

    private Version() {}

    public static synchronized String get() {
        if (cached != null) return cached;
        String v = "unknown";
        try {
            InputStream in = Version.class.getClassLoader().getResourceAsStream("dankvotes.properties");
            if (in != null) {
                try {
                    Properties p = new Properties();
                    p.load(in);
                    String s = p.getProperty("version", "").trim();
                    if (!s.isEmpty() && !s.contains("${")) v = s;
                } finally {
                    in.close();
                }
            }
        } catch (Exception ignored) {}
        cached = v;
        return v;
    }
}
