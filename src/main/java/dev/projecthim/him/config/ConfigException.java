package dev.projecthim.him.config;

import java.util.List;

/** Thrown when config.yml contains errors. Carries every problem found, not just the first. */
public final class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient List<String> errors;
    private final transient List<String> warnings;

    public ConfigException(List<String> errors, List<String> warnings) {
        super("config.yml 有 " + errors.size() + " 個錯誤");
        this.errors = List.copyOf(errors);
        this.warnings = List.copyOf(warnings);
    }

    public List<String> errors() {
        return errors;
    }

    public List<String> warnings() {
        return warnings;
    }
}
