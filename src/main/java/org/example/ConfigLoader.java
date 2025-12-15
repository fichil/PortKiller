package org.example;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class ConfigLoader {

    private static Map root;

    static {
        loadYaml();
    }

    /** 一次性加载 application.yml */
    @SuppressWarnings("unchecked")
    private static void loadYaml() {
        try {
            Yaml yaml = new Yaml();
            InputStream in = ConfigLoader.class
                    .getClassLoader()
                    .getResourceAsStream("application.yml");

            if (in == null) {
                throw new RuntimeException("application.yml not found in classpath");
            }
            root = (Map) yaml.load(in);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load application.yml", e);
        }
    }

    /** 获取 portKiller 节点 */
    @SuppressWarnings("unchecked")
    private static Map getPortKiller() {
        Object pk = root.get("portKiller");
        if (pk == null || !(pk instanceof Map)) {
            throw new RuntimeException("Missing 'portKiller' config section");
        }
        return (Map) pk;
    }

    /** 端口列表 */
    @SuppressWarnings("unchecked")
    public static List<Integer> getPorts() {
        Map pk = getPortKiller();
        Object ports = pk.get("ports");
        if (ports == null || !(ports instanceof List)) {
            throw new RuntimeException("portKiller.ports must be a list");
        }
        return (List<Integer>) ports;
    }

    /** 是否只处理 LISTENING */
    public static boolean isOnlyListening() {
        Map pk = getPortKiller();
        Object v = pk.get("onlyListening");
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : true;
    }

    /** 是否强制 kill（taskkill /F） */
    public static boolean isForceKill() {
        Map pk = getPortKiller();
        Object v = pk.get("forceKill");
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : true;
    }

    /** 是否 Dry Run（不真正 kill） */
    public static boolean isDryRun() {
        Map pk = getPortKiller();
        Object v = pk.get("dryRun");
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : false;
    }
}
