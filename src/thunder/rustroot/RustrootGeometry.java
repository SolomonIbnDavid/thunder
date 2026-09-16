package thunder.rustroot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure prospecting geometry, kept independent of the game UI for testing. */
public final class RustrootGeometry {
    public static final double TILE = 11.0;
    private static final Set<String> ORES = new HashSet<>(Arrays.asList(
        "argentite", "silvershine", "blackcoal", "cassiterite", "chalcopyrite",
        "cinnabar", "cuprite", "wineglance", "galena", "hematite", "bloodstone",
        "hornsilver", "ilmenite", "heavyearth", "leadglance", "limonite", "ironochre",
        "magnetite", "blackore", "malachite", "nagyagite", "leafore", "peacockore",
        "petzite", "direvein", "sylvanite", "schrifterz", "meteorite"
    ));

    private RustrootGeometry() {}

    public static boolean isOre(String value) {
        if(value == null) {return false;}
        String normalized = value.toLowerCase().replaceAll("[^a-z0-9]", "");
        for(String ore : ORES) {
            if(normalized.contains(ore)) {return true;}
        }
        return false;
    }

    public static final class Point {
        public final double x, y;

        public Point(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double distance(Point other) {
            return Math.hypot(x - other.x, y - other.y);
        }
    }

    public static final class Reading {
        public final Point origin;
        public final double a1, a2, quality, range;

        public Reading(Point origin, double a1, double a2, double quality, double range) {
            this.origin = origin;
            this.a1 = a1;
            this.a2 = a2;
            this.quality = quality;
            this.range = Math.max(TILE, range);
        }

        public boolean contains(Point p) {
            if(origin.distance(p) > range + (TILE * 0.75)) {return false;}
            // Dowse renders y as -world-y, so convert the world-space vector back
            // into the angle convention sent by the server.
            double angle = Math.atan2(-(p.y - origin.y), p.x - origin.x);
            return between(angle, a1, a2);
        }

        public double middleAngle() {
            double width = positive(a2 - a1);
            return normalize(a1 + (width / 2.0));
        }
    }

    public static List<Point> candidates(List<Reading> readings) {
        if(readings == null || readings.isEmpty()) {return Collections.emptyList();}
        double minX = -Double.MAX_VALUE, minY = -Double.MAX_VALUE;
        double maxX = Double.MAX_VALUE, maxY = Double.MAX_VALUE;
        for(Reading r : readings) {
            minX = Math.max(minX, r.origin.x - r.range);
            minY = Math.max(minY, r.origin.y - r.range);
            maxX = Math.min(maxX, r.origin.x + r.range);
            maxY = Math.min(maxY, r.origin.y + r.range);
        }
        if(minX > maxX || minY > maxY) {return Collections.emptyList();}

        int x0 = (int)Math.floor(minX / TILE);
        int y0 = (int)Math.floor(minY / TILE);
        int x1 = (int)Math.ceil(maxX / TILE);
        int y1 = (int)Math.ceil(maxY / TILE);
        List<Point> out = new ArrayList<>();
        for(int ty = y0; ty <= y1; ty++) {
            for(int tx = x0; tx <= x1; tx++) {
                Point p = new Point((tx + 0.5) * TILE, (ty + 0.5) * TILE);
                boolean valid = true;
                for(Reading r : readings) {
                    if(!r.contains(p)) {valid = false; break;}
                }
                if(valid) {out.add(p);}
            }
        }
        return out;
    }

    public static Point suggest(List<Reading> readings) {
        if(readings == null || readings.isEmpty()) {return null;}
        Reading last = readings.get(readings.size() - 1);
        if(readings.size() == 1) {
            double a = last.middleAngle();
            double step = Math.min(7.0 * TILE, Math.max(3.0 * TILE, last.range * 0.35));
            // Move perpendicular to the first cone to create a useful baseline.
            return snap(new Point(last.origin.x + (Math.sin(a) * step),
                                  last.origin.y + (Math.cos(a) * step)));
        }
        List<Point> possible = candidates(readings);
        if(possible.isEmpty()) {return null;}
        double x = 0, y = 0;
        for(Point p : possible) {x += p.x; y += p.y;}
        return snap(new Point(x / possible.size(), y / possible.size()));
    }

    public static Point snap(Point p) {
        return new Point((Math.floor(p.x / TILE) + 0.5) * TILE,
                         (Math.floor(p.y / TILE) + 0.5) * TILE);
    }

    private static boolean between(double value, double start, double end) {
        return positive(value - start) <= positive(end - start) + 1e-9;
    }

    private static double positive(double angle) {
        double twoPi = Math.PI * 2.0;
        angle %= twoPi;
        return angle < 0 ? angle + twoPi : angle;
    }

    private static double normalize(double angle) {
        angle = positive(angle);
        return angle > Math.PI ? angle - (Math.PI * 2.0) : angle;
    }
}
