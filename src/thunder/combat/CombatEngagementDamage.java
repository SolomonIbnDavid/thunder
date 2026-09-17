package thunder.combat;

import haven.GobDamageInfo;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Accumulates positive damage-counter deltas for each active combat relation. */
public final class CombatEngagementDamage {
    public interface Source {
        GobDamageInfo.DamageSnapshot snapshot(long gobId);
    }

    private static final class Track {
        GobDamageInfo.DamageSnapshot last;
        long armor;
        long shp;
        long hhp;

        Track(GobDamageInfo.DamageSnapshot baseline) {
            last = baseline;
        }
    }

    private final Map<Long, Track> tracks = new HashMap<>();

    public void update(Collection<Long> activeRelations, Source source) {
        Set<Long> active = new HashSet<>();
        if(activeRelations != null)
            active.addAll(activeRelations);
        for(Iterator<Map.Entry<Long, Track>> iterator = tracks.entrySet().iterator(); iterator.hasNext();) {
            if(!active.contains(iterator.next().getKey()))
                iterator.remove();
        }
        for(Long id : active) {
            if(id == null)
                continue;
            GobDamageInfo.DamageSnapshot current = safe(source == null ? null : source.snapshot(id));
            Track track = tracks.get(id);
            if(track == null) {
                tracks.put(id, new Track(current));
                continue;
            }
            track.armor += positive(current.armor, track.last.armor);
            track.shp += positive(current.shp, track.last.shp);
            track.hhp += positive(current.hhp, track.last.hhp);
            track.last = current;
        }
    }

    public GobDamageInfo.DamageSnapshot snapshot(long gobId) {
        Track track = tracks.get(gobId);
        if(track == null)
            return(GobDamageInfo.DamageSnapshot.empty());
        return(new GobDamageInfo.DamageSnapshot(saturate(track.armor), saturate(track.shp), saturate(track.hhp)));
    }

    public boolean tracks(long gobId) {
        return(tracks.containsKey(gobId));
    }

    public void clear() {
        tracks.clear();
    }

    private static long positive(int current, int previous) {
        return(Math.max(0L, (long)current - previous));
    }

    private static int saturate(long value) {
        return(value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)Math.max(0, value));
    }

    private static GobDamageInfo.DamageSnapshot safe(GobDamageInfo.DamageSnapshot snapshot) {
        return(snapshot == null ? GobDamageInfo.DamageSnapshot.empty() : snapshot);
    }
}
