package haven.pathfinding;

import haven.pathfinding.PfTestRunner.Run;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Contract tests for the retained, local-only Pathfinder probe surface. */
public class PfTestRunnerTest {
   private static final List<String> LOCAL_SCENARIOS = List.of(
      "observe",
      "basement_cabinet_identify",
      "move_to_auto_open_ground",
      "move_to_auto_obstacle_corridor",
      "select_open_ground",
      "select_obstacle_corridor",
      "select_boulder_approach",
      "select_waterline_approach",
      "surface_long_open_ground",
      "surface_single_tree_detour",
      "surface_dense_forest",
      "surface_clustered_obstacles",
      "surface_one_tile_corridor",
      "surface_diagonal_corridor",
      "surface_buildings_fences",
      "surface_water_boundary",
      "surface_cliff_boundary",
      "surface_moving_neutral",
      "surface_hostile_exclusion",
      "surface_unknown_geometry",
      "interact_forageable",
      "interact_tree",
      "interact_boulder",
      "interact_cupboard",
      "interact_door_gate",
      "interact_field_crop",
      "interact_narrow_interior"
   );

   @Test
   void exposesOnlyTheRetainedLocalScenarios() {
      Assertions.assertEquals(LOCAL_SCENARIOS, PfTestRunner.knownScenarios());
      Assertions.assertFalse(PfTestRunner.knownScenarios().stream().anyMatch(name ->
         name.contains("campaign") || name.contains("transition")
            || name.contains("known_long") || name.contains("explore_frontier")));
   }

   @Test
   void registryResolvesEveryAllowlistedScenarioByName() {
      for (String name : LOCAL_SCENARIOS) {
         PfTestRunner.Scenario scenario = PfScenarioRegistry.get(name);
         Assertions.assertNotNull(scenario, name);
         Assertions.assertEquals(name, scenario.name());
      }
      Assertions.assertNull(PfScenarioRegistry.get("move_to_marker"));
      Assertions.assertNull(PfScenarioRegistry.get("campaign_recorded"));
      Assertions.assertNull(PfScenarioRegistry.get(null));
   }

   @Test
   void rejectsUnknownBlankAndCommandLikeNames() {
      Assertions.assertNotNull(PfTestRunner.validateScenario(null));
      Assertions.assertNotNull(PfTestRunner.validateScenario(""));
      Assertions.assertNotNull(PfTestRunner.validateScenario("walk"));
      Assertions.assertNotNull(PfTestRunner.validateScenario("observe; :cmd evil"));
      Assertions.assertNull(PfTestRunner.validateScenario(" observe "));
   }

   @Test
   void verdictUsesOnlyActualFailures() {
      Assertions.assertEquals("PASS", PfTestRunner.verdictOf(List.of(
         PfTestRunner.check("present", true, "ok"),
         PfTestRunner.skip("optional", "not applicable")
      )));
      Assertions.assertEquals("FAIL", PfTestRunner.verdictOf(List.of(
         PfTestRunner.check("present", true, "ok"),
         PfTestRunner.check("arrival", false, "stopped short")
      )));
   }

   @Test
   void runLockRejectsConcurrencyAndReleasesCleanly() {
      Run first = new Run("observe");
      try {
         Assertions.assertTrue(PfTestRunner.beginRun(first));
         Assertions.assertFalse(PfTestRunner.beginRun(new Run("observe")));
         Assertions.assertSame(first, PfTestRunner.currentRun());
      } finally {
         PfTestRunner.finishRun(first);
      }
      Assertions.assertNull(PfTestRunner.currentRun());
   }

   @Test
   void cancellationIsSafeAndIdempotent() {
      Assertions.assertNull(PfTestRunner.cancelRun());
      Run run = new Run("observe");
      try {
         Assertions.assertTrue(PfTestRunner.beginRun(run));
         Assertions.assertSame(run, PfTestRunner.cancelRun());
         Assertions.assertSame(run, PfTestRunner.cancelRun());
      } finally {
         PfTestRunner.finishRun(run);
      }
   }

   @Test
   void completedLocalResultCanBeRetrieved() {
      String id = "observe-local-contract";
      PfTestRunner.rememberResult(new JSONObject()
         .put("run_id", id).put("scenario", "observe")
         .put("status", "completed").put("verdict", "PASS"));
      JSONObject result = PfTestRunner.result(id);
      Assertions.assertNotNull(result);
      Assertions.assertEquals("PASS", result.getString("verdict"));
   }

   @Test
   void observeWithoutGameStateFailsClosed() throws Exception {
      JSONObject body = new ObserveScenario().execute(new Run("observe"), null);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      Assertions.assertFalse(body.has("scene"));
   }
}
