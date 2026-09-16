package haven.pathfinding;

import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One shared, immutable view of the nearby game objects. */
public final class WorldObjectRegistry {
   private static final Map<Long, String> LAST_RESOURCE = new ConcurrentHashMap<Long, String>();

   private WorldObjectRegistry() {}

   public enum Category {
      STOCKPILE, FURNITURE, SMELTER, CONTAINER, VEHICLE, TREE, LOG, PLANT, CREATURE, OTHER, UNRESOLVED
   }

   public static final class Entry {
      public final Gob gob;
      public final long id;
      public final Coord2d position;
      public final String resource;
      public final Category category;
      public final boolean loading;
      public final MovementScene.GobGeom geometry;

      Entry(Gob gob, String resource, boolean loading, MovementScene.GobGeom geometry) {
         this.gob = gob;
         this.id = gob.id;
         this.position = gob.rc;
         this.resource = resource;
         this.loading = loading;
         this.geometry = geometry;
         this.category = category(resource, loading);
      }
   }

   public static final class Snapshot {
      public final List<Entry> objects;
      public final int unresolved;

      Snapshot(List<Entry> objects, int unresolved) {
         this.objects = Collections.unmodifiableList(objects);
         this.unresolved = unresolved;
      }

      public List<MovementScene.GobGeom> pathfindingGeometry() {
         List<MovementScene.GobGeom> out = new ArrayList<MovementScene.GobGeom>();
         for (Entry e : objects) {
            if (e.geometry != null && MovementScene.includeInScene(e.geometry)) out.add(e.geometry);
         }
         out.sort(Comparator.comparingDouble(a -> a.polyDist));
         return out;
      }

      public List<Entry> category(Category wanted) {
         List<Entry> out = new ArrayList<Entry>();
         for (Entry e : objects) if (e.category == wanted) out.add(e);
         return out;
      }
   }

   public static Snapshot snapshot(GameUI gui, Gob player, double reach) {
      if (gui == null || gui.ui == null || gui.ui.sess == null) return new Snapshot(Collections.emptyList(), 0);
      List<Gob> visible = new ArrayList<Gob>();
      synchronized (gui.ui.sess.glob.oc) {
         for (Gob gob : gui.ui.sess.glob.oc) {
            if (gob == null || gob == player || gob.virtual || gob.id < 0 || gob.rc == null || gob.disposed()) continue;
            if (player != null && player.rc != null && reach > 0 && gob.rc.dist(player.rc) > reach) continue;
            visible.add(gob);
         }
      }
      List<Entry> entries = new ArrayList<Entry>(visible.size());
      int unresolved = 0;
      for (Gob gob : visible) {
         String resource = null;
         boolean loading = false;
         try {
            resource = gob.resid();
            if (resource != null && !resource.isEmpty()) LAST_RESOURCE.put(gob.id, resource);
         } catch (Loading e) {
            loading = true;
            resource = LAST_RESOURCE.get(gob.id);
         } catch (RuntimeException e) {
            loading = true;
            resource = LAST_RESOURCE.get(gob.id);
         }
         if (resource == null || resource.isEmpty()) {
            unresolved++;
            resource = "";
         }
         MovementScene.GobGeom geometry = MovementScene.gobGeom(player, gob);
         if ((geometry.resid == null || geometry.resid.isEmpty()) && !resource.isEmpty()) geometry.resid = resource;
         entries.add(new Entry(gob, resource, loading, geometry));
      }
      entries.sort(Comparator.comparingLong(e -> e.id));
      return new Snapshot(entries, unresolved);
   }

   static Category category(String resource, boolean loading) {
      if (resource == null || resource.isEmpty()) return Category.UNRESOLVED;
      String r = resource.toLowerCase(Locale.ROOT);
      if (r.contains("/stockpile-")) return Category.STOCKPILE;
      if (r.contains("smelter") || r.contains("fineryforge") || r.contains("crucible") ||
          r.contains("steelbox") || r.endsWith("/kiln") || r.endsWith("/oven")) return Category.SMELTER;
      if (r.contains("/vehicle/") || r.endsWith("/cart") || r.endsWith("/wagon")) return Category.VEHICLE;
      if (r.contains("/trees/") && (r.endsWith("log") || r.endsWith("trunk"))) return Category.LOG;
      if (r.contains("/trees/")) return Category.TREE;
      if (r.contains("/plants/")) return Category.PLANT;
      if (r.contains("/kritter/")) return Category.CREATURE;
      if (r.contains("cupboard") || r.contains("chest") || r.contains("cabinet") || r.contains("crate") || r.contains("barrel")) return Category.CONTAINER;
      if (r.contains("dframe") || r.contains("furn/") || r.contains("table") || r.contains("chair") || r.contains("bed")) return Category.FURNITURE;
      return Category.OTHER;
   }
}
