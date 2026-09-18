package thunder.mining;

import haven.Area;
import haven.Coord;

import java.util.HashMap;
import java.util.Map;

/** Current-login saved-map supply areas for Miner Bot V3. */
public final class MinerBotV3ZoneStore {
    public static final String ROLE_STORAGE = "miner-v3-storage";
    public static final String ROLE_WATER = "miner-v3-water";
    public static final String ROLE_FOOD = "miner-v3-food";

    public static final class SavedArea {
        public final long segmentId;
        public final Area area;

        public SavedArea(long segmentId, Area area) {
            if(area == null) throw new IllegalArgumentException("area is required");
            this.segmentId = segmentId;
            this.area = copy(area);
        }

        public Area liveArea(Coord sessionTile) {
            if(sessionTile == null) return null;
            return new Area(area.ul.sub(sessionTile), area.br.sub(sessionTile));
        }

        public boolean isOnSegment(long segment) {return segmentId == segment;}

        public static SavedArea fromLive(long segmentId, Coord sessionTile, Area live) {
            if(sessionTile == null || live == null) throw new IllegalArgumentException("session tile and area are required");
            return new SavedArea(segmentId,
                new Area(live.ul.add(sessionTile), live.br.add(sessionTile)));
        }
    }

    private static final MinerBotV3ZoneStore INSTANCE = new MinerBotV3ZoneStore();
    private final Map<String, SavedArea> zones = new HashMap<>();
    private Object sessionKey;

    private MinerBotV3ZoneStore() {}

    public static MinerBotV3ZoneStore get() {return INSTANCE;}

    public synchronized void bind(Object currentSession) {
        if(sessionKey != currentSession) {
            zones.clear();
            sessionKey = currentSession;
        }
    }

    public synchronized SavedArea get(Object currentSession, String role) {
        bind(currentSession);
        SavedArea area = zones.get(role);
        return area == null ? null : new SavedArea(area.segmentId, area.area);
    }

    public synchronized void put(Object currentSession, String role, SavedArea area) {
        bind(currentSession);
        zones.put(role, new SavedArea(area.segmentId, area.area));
    }

    public synchronized void clear(Object currentSession, String role) {
        bind(currentSession);
        zones.remove(role);
    }

    private static Area copy(Area area) {
        return new Area(new Coord(area.ul), new Coord(area.br));
    }
}
