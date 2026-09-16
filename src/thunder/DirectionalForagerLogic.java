package thunder;

import haven.Coord2d;
import java.util.*;

/** Pure decisions used by the live runner and offline tests. */
public final class DirectionalForagerLogic {
    public enum Direction {
        NORTH(0, -1), SOUTH(0, 1), WEST(-1, 0), EAST(1, 0);
        final int dx, dy;
        Direction(int dx, int dy) {this.dx = dx; this.dy = dy;}
    }

    public static final class Candidate {
        public final long id;
        public final String key;
        public final Coord2d position;
        public Candidate(long id, String key, Coord2d position) {this.id = id; this.key = key; this.position = position;}
    }

    private DirectionalForagerLogic() {}

    public static Coord2d forward(Coord2d from, Direction direction, double distance) {
        return from.add(direction.dx * distance, direction.dy * distance);
    }

    /** A probe direction rotated around the player's preferred compass heading. */
    public static Coord2d probe(Coord2d from, Direction direction, double distance, double angle) {
        double cs = Math.cos(angle), sn = Math.sin(angle);
        double x = direction.dx * cs - direction.dy * sn;
        double y = direction.dx * sn + direction.dy * cs;
        return from.add(x * distance, y * distance);
    }

    public static double forwardProgress(Coord2d from, Coord2d to, Direction direction) {
        return (to.x - from.x) * direction.dx + (to.y - from.y) * direction.dy;
    }

    public static boolean safeFrom(Coord2d point, Collection<Coord2d> dangers, double radius) {
        if(point == null) return false;
        if(dangers == null || radius <= 0.0) return true;
        for(Coord2d danger : dangers) {
            if(danger != null && point.dist(danger) < radius) return false;
        }
        return true;
    }

    public static Candidate nearest(Coord2d player, Collection<Candidate> candidates, Set<String> selected, Set<Long> failed) {
        Candidate best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        if(player == null || candidates == null || selected == null) return null;
        for(Candidate c : candidates) {
            if(c == null || c.position == null || !selected.contains(c.key) || (failed != null && failed.contains(c.id))) continue;
            double distance = player.dist(c.position);
            if(distance < bestDistance || (distance == bestDistance && (best == null || c.id < best.id))) {
                best = c; bestDistance = distance;
            }
        }
        return best;
    }
}
