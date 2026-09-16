package haven.dev;

import haven.Config.Variable;
import java.lang.reflect.Field;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DevControlPortTest {
   private static final String KEY = "haven.dev.control.port";

   @Test
   void defaultIsDisabledWhenPropertyUnset() throws Exception {
      String prev = System.getProperty("haven.dev.control.port");

      try {
         System.clearProperty("haven.dev.control.port");
         resetCache();
         Assertions.assertEquals(0, (Integer)DevControl.PORT.get(), "DevControl must be off by default (0 disables the bind)");
      } finally {
         restore(prev);
         resetCache();
      }
   }

   @Test
   void explicitPortStillEnablesServer() throws Exception {
      String prev = System.getProperty("haven.dev.control.port");

      try {
         System.setProperty("haven.dev.control.port", "18761");
         resetCache();
         Assertions.assertEquals(18761, (Integer)DevControl.PORT.get(), "explicit -Dhaven.dev.control.port=18761 must still enable the server");
      } finally {
         restore(prev);
         resetCache();
      }
   }

   @Test
   void pfScenarioListSerializesWithBundledJsonLibrary() {
      JSONObject result = DevControl.pfScenarios();
      Assertions.assertTrue(result.getBoolean("ok"));
      java.util.List<String> known = haven.pathfinding.PfTestRunner.knownScenarios();
      Assertions.assertEquals(known.size(), result.getJSONArray("scenarios").length());
      Assertions.assertEquals("observe", result.getJSONArray("scenarios").getString(0));
      Assertions.assertEquals("basement_cabinet_identify", result.getJSONArray("scenarios").getString(1));
      Assertions.assertTrue(known.contains("move_to_auto_open_ground"));
      Assertions.assertTrue(known.contains("move_to_auto_obstacle_corridor"));
      Assertions.assertTrue(known.contains("select_open_ground"));
      Assertions.assertTrue(known.contains("select_obstacle_corridor"));
      Assertions.assertTrue(known.contains("select_boulder_approach"));
      Assertions.assertTrue(known.contains("select_waterline_approach"));
      Assertions.assertTrue(known.contains("surface_long_open_ground"));
      Assertions.assertFalse(known.contains("move_to_marker"));
      Assertions.assertFalse(known.contains("campaign_recorded"));
      Assertions.assertFalse(known.contains("transition_cave"));
   }

   private static void resetCache() throws Exception {
      Field inited = Variable.class.getDeclaredField("inited");
      inited.setAccessible(true);
      inited.setBoolean(DevControl.PORT, false);
   }

   private static void restore(String prev) {
      if (prev == null) {
         System.clearProperty("haven.dev.control.port");
      } else {
         System.setProperty("haven.dev.control.port", prev);
      }
   }
}
