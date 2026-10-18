package dev.projecthim.him.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Typed, validating access to a configuration section. Problems are collected instead of thrown so
 * an administrator sees every mistake at once. Errors make the whole load fail; warnings are reported
 * and a safe value is used instead.
 */
final class ConfigReader {

    private final ConfigurationSection root;
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    ConfigReader(ConfigurationSection root) {
        this.root = root;
    }

    List<String> errors() {
        return errors;
    }

    List<String> warnings() {
        return warnings;
    }

    void error(String path, String message) {
        errors.add(path + ": " + message);
    }

    void warn(String path, String message) {
        warnings.add(path + ": " + message);
    }

    boolean bool(String path, boolean def) {
        if (!root.contains(path)) {
            warn(path, "缺少此設定，使用預設值 " + def);
            return def;
        }
        Object v = root.get(path);
        if (v instanceof Boolean b) {
            return b;
        }
        error(path, "必須是 true 或 false，目前為「" + v + "」");
        return def;
    }

    double number(String path, double def, double min, double max) {
        if (!root.contains(path)) {
            warn(path, "缺少此設定，使用預設值 " + def);
            return def;
        }
        Object v = root.get(path);
        if (!(v instanceof Number n)) {
            error(path, "必須是數字，目前為「" + v + "」");
            return def;
        }
        double d = n.doubleValue();
        if (!Double.isFinite(d) || d < min || d > max) {
            error(path, "數值 " + d + " 超出允許範圍 [" + min + ", " + max + "]");
            return def;
        }
        return d;
    }

    int integer(String path, int def, int min, int max) {
        if (!root.contains(path)) {
            warn(path, "缺少此設定，使用預設值 " + def);
            return def;
        }
        Object v = root.get(path);
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            error(path, "必須是整數，目前為「" + v + "」");
            return def;
        }
        int i = n.intValue();
        if (i < min || i > max) {
            error(path, "數值 " + i + " 超出允許範圍 [" + min + ", " + max + "]");
            return def;
        }
        return i;
    }

    String string(String path, String def) {
        if (!root.contains(path)) {
            warn(path, "缺少此設定，使用預設值「" + def + "」");
            return def;
        }
        Object v = root.get(path);
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        error(path, "必須是文字");
        return def;
    }

    <E extends Enum<E>> E enumValue(String path, Class<E> type, E def) {
        String raw = string(path, def.name());
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            List<String> allowed = new ArrayList<>();
            for (E c : type.getEnumConstants()) {
                allowed.add(c.name().toLowerCase(Locale.ROOT));
            }
            error(path, "無效的值「" + raw + "」，允許的值：" + String.join(", ", allowed));
            return def;
        }
    }

    boolean has(String path) {
        return root.contains(path);
    }

    List<String> stringList(String path) {
        if (!root.contains(path)) {
            warn(path, "缺少此設定，使用空清單");
            return List.of();
        }
        if (!root.isList(path)) {
            error(path, "必須是清單（例如 [a, b]）");
            return List.of();
        }
        List<String> out = new ArrayList<>();
        // getList(path) (not getList(path, def)): only the former falls back to the plugin's bundled
        // defaults when an older config.yml does not have the setting yet.
        List<?> values = root.getList(path);
        for (Object o : values == null ? List.of() : values) {
            if (o == null) {
                continue;
            }
            out.add(String.valueOf(o));
        }
        return out;
    }

    double[] numberArray(String path, double[] def, double min, double max) {
        if (!root.contains(path)) {
            warn(path, "缺少此設定，使用預設值");
            return def.clone();
        }
        if (!root.isList(path)) {
            error(path, "必須是 " + def.length + " 個數字的清單");
            return def.clone();
        }
        List<?> listed = root.getList(path);
        List<?> raw = listed == null ? List.of() : listed;
        if (raw.size() != def.length) {
            error(path, "必須剛好有 " + def.length + " 個數字（每個階段一個），目前有 " + raw.size() + " 個");
            return def.clone();
        }
        double[] out = new double[def.length];
        for (int i = 0; i < raw.size(); i++) {
            if (!(raw.get(i) instanceof Number n) || !Double.isFinite(n.doubleValue())
                    || n.doubleValue() < min || n.doubleValue() > max) {
                error(path, "第 " + (i + 1) + " 個值「" + raw.get(i) + "」必須是介於 " + min + " 與 " + max + " 的數字");
                return def.clone();
            }
            out[i] = n.doubleValue();
        }
        return out;
    }
}
