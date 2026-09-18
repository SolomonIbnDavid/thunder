package thunder.mining;

import auto.MinerBotV3Logic;
import haven.Coord;

/** Current-login mining-frontier checkpoint for Miner Bot V3. */
public final class MinerBotV3AnchorStore {
    public static final String ROLE_ANCHOR = "miner-v3-anchor";

    public static final class SavedAnchor {
        public final long segmentId;
        public final Coord tile;
        public final MinerBotV3Logic.Direction heading;
        public final boolean manuallyPicked;

        public SavedAnchor(long segmentId, Coord tile,
                           MinerBotV3Logic.Direction heading,
                           boolean manuallyPicked) {
            if(tile == null) throw new IllegalArgumentException("anchor tile is required");
            if(heading == null) throw new IllegalArgumentException("anchor heading is required");
            this.segmentId = segmentId;
            this.tile = new Coord(tile);
            this.heading = heading;
            this.manuallyPicked = manuallyPicked;
        }

        public Coord liveTile(Coord sessionTile) {
            return sessionTile == null ? null : tile.sub(sessionTile);
        }

        public boolean isOnSegment(long segment) {return segmentId == segment;}

        public static SavedAnchor fromLive(long segmentId, Coord sessionTile,
                                           Coord liveTile,
                                           MinerBotV3Logic.Direction heading,
                                           boolean manuallyPicked) {
            if(sessionTile == null || liveTile == null)
                throw new IllegalArgumentException("session and live anchor tiles are required");
            return new SavedAnchor(segmentId, liveTile.add(sessionTile), heading, manuallyPicked);
        }
    }

    private static final MinerBotV3AnchorStore INSTANCE = new MinerBotV3AnchorStore();
    private Object sessionKey;
    private SavedAnchor anchor;

    private MinerBotV3AnchorStore() {}

    public static MinerBotV3AnchorStore get() {return INSTANCE;}

    public synchronized void bind(Object currentSession) {
        if(sessionKey != currentSession) {
            anchor = null;
            sessionKey = currentSession;
        }
    }

    public synchronized SavedAnchor get(Object currentSession) {
        bind(currentSession);
        return copy(anchor);
    }

    public synchronized void put(Object currentSession, SavedAnchor saved) {
        bind(currentSession);
        anchor = copy(saved);
    }

    public synchronized void clear(Object currentSession) {
        bind(currentSession);
        anchor = null;
    }

    private static SavedAnchor copy(SavedAnchor saved) {
        return saved == null ? null : new SavedAnchor(saved.segmentId, saved.tile,
            saved.heading, saved.manuallyPicked);
    }
}
