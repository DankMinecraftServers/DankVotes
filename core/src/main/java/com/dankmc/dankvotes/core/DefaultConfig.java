package com.dankmc.dankvotes.core;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.logging.Logger;

/**
 * Writes the bundled, commented config.yml into a plugin's data folder on first start.
 *
 * The default lives at a path only DankVotes uses ({@link #RESOURCE}) rather than a bare
 * "config.yml": on Sponge (and anywhere class loaders ask their parent first) a bare name
 * could find another mod's or the server's own config.yml.
 */
public final class DefaultConfig {

    public static final String FILE_NAME = "config.yml";
    public static final String RESOURCE = "com/dankmc/dankvotes/core/config.yml";

    private DefaultConfig() {}

    /** Copy config.yml out of the jar unless the file already exists. Returns the file. */
    public static File saveIfMissing(File dataFolder, Class<?> anchor, Logger logger) {
        File target = new File(dataFolder, FILE_NAME);
        if (target.exists()) return target;
        try {
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                logger.warning("Could not create " + dataFolder + " - using the default settings.");
                return target;
            }
            InputStream in = anchor.getClassLoader().getResourceAsStream(RESOURCE);
            if (in == null) {
                logger.warning("The jar has no bundled " + FILE_NAME + " - using the default settings.");
                return target;
            }
            try {
                Files.copy(in, target.toPath());
            } finally {
                in.close();
            }
        } catch (Exception e) {
            logger.warning("Could not write the default " + FILE_NAME + ": " + e.getMessage());
        }
        return target;
    }
}
