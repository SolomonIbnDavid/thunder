package thunder.woodcut;

import haven.Area;

import java.util.HashMap;
import java.util.Map;

/** Session-only areas used by the Log Cutter bot. */
public final class WoodCutZoneStore {
    public static final String ROLE_LOGS = "woodcut-logs";
    public static final String ROLE_OUTPUT = "woodcut-output";

    private static final WoodCutZoneStore INSTANCE = new WoodCutZoneStore();
    private final Map<String, Area> zones = new HashMap<>();

    private WoodCutZoneStore() {}

    public static WoodCutZoneStore get() {return INSTANCE;}
    public synchronized Area get(String role) {return zones.get(role);}
    public synchronized void put(String role, Area area) {zones.put(role, area);}
    public synchronized void clearAll() {zones.clear();}
}
