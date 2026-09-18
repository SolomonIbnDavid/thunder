package auto;

import haven.Coord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure geometry and policy rules for Miner Bot V3. */
public final class MinerBotV3Logic {
    public static final int LEG_TILES = 11;
    public static final int COLUMN_STONES = 30;
    public static final int MAX_NO_PROGRESS_REDRAWS = 3;
    public static final double LOW_ENERGY = 0.25;
    public static final int EAT_UNTIL_PERCENT = 80;

    private MinerBotV3Logic() {}

    public enum Direction {
        NORTH(0, -1), SOUTH(0, 1), EAST(1, 0), WEST(-1, 0);

        public final int dx;
        public final int dy;

        Direction(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        public Coord step() {return Coord.of(dx, dy);}
        public Direction right() {
            switch(this) {
            case NORTH: return EAST;
            case EAST: return SOUTH;
            case SOUTH: return WEST;
            default: return NORTH;
            }
        }
        public Direction left() {return right().right().right();}

        public static Direction parse(String value) {
            String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            switch(text) {
            case "n": case "north": return NORTH;
            case "s": case "south": return SOUTH;
            case "e": case "east": return EAST;
            case "w": case "west": return WEST;
            default: throw new IllegalArgumentException("Unknown direction '" + value + "' (use n/s/e/w)");
            }
        }
    }

    public static final class Line {
        public final Coord start;
        public final Coord end;

        Line(Coord start, Coord end) {
            this.start = new Coord(start);
            this.end = new Coord(end);
        }
    }

    public static Line remainingLine(Coord anchor, Direction heading, int completedTiles) {
        if(anchor == null || heading == null) throw new IllegalArgumentException("anchor and heading are required");
        int completed = Math.max(0, Math.min(LEG_TILES, completedTiles));
        Coord step = heading.step();
        Coord end = anchor.add(step.mul(LEG_TILES));
        Coord start = completed >= LEG_TILES ? end : anchor.add(step.mul(completed + 1));
        return new Line(start, end);
    }

    public static Coord endpoint(Coord anchor, Direction heading) {
        return anchor.add(heading.step().mul(LEG_TILES));
    }

    public static Coord columnTile(Coord anchor, Direction heading) {
        return endpoint(anchor, heading).add(heading.right().step());
    }

    /** Tile distance along the heading, clamped to this leg. Off-axis tiles make no progress. */
    public static int completedTiles(Coord anchor, Direction heading, Coord tile) {
        if(anchor == null || heading == null || tile == null) return 0;
        Coord delta = tile.sub(anchor);
        int along = delta.x * heading.dx + delta.y * heading.dy;
        int cross = delta.x * heading.right().dx + delta.y * heading.right().dy;
        return cross == 0 ? Math.max(0, Math.min(LEG_TILES, along)) : 0;
    }

    public enum Side {RIGHT, LEFT}

    public static final class DetourCandidate {
        public final int retreatLegs;
        public final Side side;
        public final int sideLegs;

        DetourCandidate(int retreatLegs, Side side, int sideLegs) {
            this.retreatLegs = retreatLegs;
            this.side = side;
            this.sideLegs = sideLegs;
        }

        public Direction heading(Direction original) {
            return side == Side.RIGHT ? original.right() : original.left();
        }

        @Override
        public String toString() {
            return "back=" + retreatLegs + " side=" + side + " legs=" + sideLegs;
        }
    }

    /** Exact bounded order agreed for the first V3 release. */
    public static List<DetourCandidate> detourCandidates() {
        List<DetourCandidate> out = new ArrayList<>();
        for(int retreat = 1; retreat <= 2; retreat++) {
            out.add(new DetourCandidate(retreat, Side.RIGHT, 1));
            out.add(new DetourCandidate(retreat, Side.LEFT, 1));
            out.add(new DetourCandidate(retreat, Side.RIGHT, 2));
            out.add(new DetourCandidate(retreat, Side.LEFT, 2));
        }
        return Collections.unmodifiableList(out);
    }

    public static boolean tooHardMessage(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).contains("too hard");
    }

    public static boolean needsEnergy(double energy) {
        return energy >= 0.0 && energy < LOW_ENERGY;
    }

    public static boolean energyTargetReached(double energy) {
        return energy >= EAT_UNTIL_PERCENT / 100.0;
    }

    public static boolean needsBars(int carried, int minimum) {
        return carried < Math.max(0, minimum);
    }

    /** Inclusive sequence of bounded route-history stops, ending exactly at target. */
    public static List<Integer> trailStops(int current, int target, int maxStep) {
        if(current < 0 || target < 0 || maxStep <= 0)
            throw new IllegalArgumentException("route indices must be non-negative and maxStep must be positive");
        List<Integer> stops = new ArrayList<>();
        int at = current;
        int direction = target >= current ? 1 : -1;
        while(at != target) {
            at = direction > 0 ? Math.min(target, at + maxStep) : Math.max(target, at - maxStep);
            stops.add(at);
        }
        return Collections.unmodifiableList(stops);
    }

    /** Tracks completed terrain/movement across redraw cycles. */
    public static final class RedrawProgress {
        private int best;
        private int noProgress;

        public RedrawProgress(int completed) {
            this.best = Math.max(0, Math.min(LEG_TILES, completed));
        }

        /** Returns true once three consecutive redraw cycles make no progress. */
        public boolean failedAfter(int completed) {
            int observed = Math.max(0, Math.min(LEG_TILES, completed));
            if(observed > best) {
                best = observed;
                noProgress = 0;
            } else {
                noProgress++;
            }
            return noProgress >= MAX_NO_PROGRESS_REDRAWS;
        }

        public int noProgressCount() {return noProgress;}
    }
}
