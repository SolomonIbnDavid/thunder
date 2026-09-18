package auto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BotDiagnosticsTest {
    @Test
    void cancellationCallerSkipsThreadAndBotPlumbing() {
        StackTraceElement expected = new StackTraceElement(
            "haven.MapView$Click", "run", "MapView.java", 2942);
        StackTraceElement[] trace = {
            new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1),
            new StackTraceElement("auto.Bot", "cancel", "Bot.java", 120),
            new StackTraceElement("auto.Bot", "cancelCurrent", "Bot.java", 135),
            expected,
        };

        assertEquals(expected.toString(), Bot.cancellationCaller(trace));
    }

    @Test
    void cancellationCallerHandlesMissingTrace() {
        assertEquals("unknown", Bot.cancellationCaller(null));
        assertEquals("unknown", Bot.cancellationCaller(new StackTraceElement[0]));
    }
}
