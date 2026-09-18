package thunder;

import haven.Config;
import haven.Coord2d;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persistent, current-world observations used by the casting heat map. */
public final class FishingSamples {
    private static final String FILE = "fishing-casting-samples.json";

    public static final class Sample implements SpatialPeakSearch.Sample {
	public final String species, tackle;
	public final int bite, land;
	public final Coord2d cast, player;
	public final double moon;
	public final int hour, minute;
	public final long at;

	public Sample(String species, String tackle, int bite, int land, Coord2d cast, Coord2d player,
		      double moon, int hour, int minute, long at) {
	    this.species = species;
	    this.tackle = tackle;
	    this.bite = bite;
	    this.land = land;
	    this.cast = cast;
	    this.player = player;
	    this.moon = moon;
	    this.hour = hour;
	    this.minute = minute;
	    this.at = at;
	}

	@Override public Coord2d point() {return cast;}
	@Override public double value() {return bite;}
    }

    private final List<Sample> samples = new ArrayList<>();

    public FishingSamples() {load();}

    public synchronized void add(Sample sample) {
	if(sample == null || sample.cast == null || sample.species == null) {return;}
	/* One server window is one observation. Replace an exact duplicate rather
	 * than letting retries or UI ticks overweight the fitted surface. */
	for(int i = samples.size() - 1; i >= 0; i--) {
	    Sample old = samples.get(i);
	    if(old.species.equals(sample.species) && old.tackle.equals(sample.tackle)
	       && old.cast.dist(sample.cast) < 0.25 && Math.abs(old.at - sample.at) < 1500) {
		samples.set(i, sample);
		save();
		return;
	    }
	}
	samples.add(sample);
	while(samples.size() > 10000) {samples.remove(0);}
	save();
    }

    public synchronized List<Sample> all() {return new ArrayList<>(samples);}

    public synchronized List<Sample> matching(String species, String tackle, double nowMoon, boolean includeStale) {
	String ns = FishingTackle.norm(species);
	List<Sample> out = new ArrayList<>();
	for(Sample s : samples) {
	    if(!FishingTackle.norm(s.species).equals(ns)) {continue;}
	    if(tackle != null && !tackle.isEmpty() && !tackle.equals(s.tackle)) {continue;}
	    if(!includeStale && FishingMoon.staleAfterFullMoon(s.moon, nowMoon)) {continue;}
	    out.add(s);
	}
	return Collections.unmodifiableList(out);
    }

    public synchronized void clear() {samples.clear(); save();}

    private void load() {
	try {
	    String raw = Config.loadFile(FILE);
	    if(raw == null || raw.isEmpty()) {return;}
	    JSONArray a = new JSONArray(raw);
	    for(int i = 0; i < a.length(); i++) {
		JSONObject o = a.getJSONObject(i);
		Coord2d cast = point(o.optJSONArray("cast"));
		Coord2d player = point(o.optJSONArray("player"));
		if(cast == null) {continue;}
		samples.add(new Sample(o.optString("species"), o.optString("tackle"), o.optInt("bite"),
			o.optInt("land"), cast, player, o.optDouble("moon"), o.optInt("hour"),
			o.optInt("minute"), o.optLong("at")));
	    }
	} catch(Exception ignored) {}
    }

    private void save() {
	JSONArray a = new JSONArray();
	for(Sample s : samples) {
	    JSONObject o = new JSONObject();
	    o.put("species", s.species).put("tackle", s.tackle).put("bite", s.bite).put("land", s.land);
	    o.put("cast", point(s.cast));
	    if(s.player != null) {o.put("player", point(s.player));}
	    o.put("moon", s.moon).put("hour", s.hour).put("minute", s.minute).put("at", s.at);
	    a.put(o);
	}
	Config.saveFile(FILE, a.toString());
    }

    private static JSONArray point(Coord2d p) {return new JSONArray().put(p.x).put(p.y);}
    private static Coord2d point(JSONArray a) {
	return a == null || a.length() < 2 ? null : Coord2d.of(a.getDouble(0), a.getDouble(1));
    }
}
