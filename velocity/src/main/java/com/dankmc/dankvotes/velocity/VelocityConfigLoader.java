package com.dankmc.dankvotes.velocity;

import com.dankmc.dankvotes.core.ConfigMapper;
import com.dankmc.dankvotes.core.DankVotesConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Loads config.yml on Velocity with SnakeYAML (part of the Velocity API) and maps it with
 * the shared {@link ConfigMapper}, so one config.yml behaves the same on every platform.
 */
public final class VelocityConfigLoader {

    private VelocityConfigLoader() {}

    public static DankVotesConfig load(Path configFile, java.util.logging.Logger logger) {
        Object root;
        try (InputStream in = Files.newInputStream(configFile)) {
            root = new Yaml().load(in);
        } catch (Exception e) {
            logger.warning("Could not read config.yml, using defaults: " + e.getMessage());
            return new DankVotesConfig();
        }
        return ConfigMapper.fromMap(root instanceof Map<?, ?> map ? map : null);
    }
}
