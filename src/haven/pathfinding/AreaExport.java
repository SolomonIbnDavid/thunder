package haven.pathfinding;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * Builds the JSON export payload for the Haven Command Center Area registry.
 *
 * <p>Vertices are in <b>durable (grid_id, local_x, local_y)</b> form: a grid
 * id is the server-assigned, re-stitch-stable address of a 100x100 map grid,
 * and the local cell is its 0..99 tile within that grid. This survives map
 * re-exports (which re-number every grid's (gx, gy) and segment), unlike the
 * old session-world-tile / segment-keyed payload. Grid ids are serialized as
 * strings because they are 64-bit values.</p>
 *
 * <p>The export path is controlled by the system property
 * {@code haven.area_export_path}, defaulting to
 * {@code /home/greg/Documents/HavenHeadlessWorker/config/haven-areas.json}.</p>
 */
final class AreaExport {

    private static final String DEFAULT_EXPORT_PATH =
        "/home/greg/Documents/HavenHeadlessWorker/config/haven-areas.json";

    private AreaExport() {
    }

    /** Returns the configured export file path. */
    static java.nio.file.Path exportPath() {
        String path = System.getProperty("haven.area_export_path", DEFAULT_EXPORT_PATH);
        return java.nio.file.Paths.get(path);
    }

    /**
     * @param gridVertices ordered polygon vertices, each {@code {gridId, lx, ly}}
     * @param name         the durable area id (user-typed)
     * @param role         the semantic role (e.g. {@code avoid}, {@code transition}); empty defaults to {@code avoid}
     * @return a JSON object matching the Area registry's per-area shape
     * @throws NullPointerException if {@code gridVertices} is null/empty or {@code name} is null/blank
     */
    static JsonObject toJson(List<long[]> gridVertices, String name, String role) {
        if (gridVertices == null || gridVertices.isEmpty()) {
            throw new NullPointerException("gridVertices must not be null or empty");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new NullPointerException("name must not be null or blank");
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("id", name.trim());
        obj.addProperty("role", (role == null || role.trim().isEmpty()) ? "avoid" : role.trim());
        obj.add("layer", com.google.gson.JsonNull.INSTANCE);
        JsonArray verts = new JsonArray();
        for (long[] gv : gridVertices) {
            JsonArray vert = new JsonArray();
            vert.add(Long.toString(gv[0]));
            vert.add((int) gv[1]);
            vert.add((int) gv[2]);
            verts.add(vert);
        }
        obj.add("grid_vertices", verts);
        obj.addProperty("exported_at_ms", System.currentTimeMillis());
        return obj;
    }
}