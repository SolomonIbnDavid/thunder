package thunder.combat;

import java.util.EnumSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts opening colors from the authoritative description of an equipped move. */
public final class CombatMoveMetadata {
    private static final Pattern REDUCTION_COLOR = Pattern.compile(
        "[µμ]\\s*\\$col\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)(?:\\s*,\\s*\\d+)?\\s*\\]",
        Pattern.CASE_INSENSITIVE);

    private CombatMoveMetadata() {
    }

    public static EnumSet<CombatAutomationRules.Opening> reducedOpenings(String paginaText) {
        EnumSet<CombatAutomationRules.Opening> result =
            EnumSet.noneOf(CombatAutomationRules.Opening.class);
        if(paginaText == null)
            return(result);

        /* The µ-prefixed color metadata is preserved by localized pagina text. */
        Matcher matcher = REDUCTION_COLOR.matcher(paginaText);
        while(matcher.find()) {
            int red = Integer.parseInt(matcher.group(1));
            int green = Integer.parseInt(matcher.group(2));
            int blue = Integer.parseInt(matcher.group(3));
            if(red == 128 && green == 255 && blue == 160)
                result.add(CombatAutomationRules.Opening.GREEN);
            else if(red == 255 && green == 255 && blue == 128)
                result.add(CombatAutomationRules.Opening.YELLOW);
            else if(red == 255 && green == 128 && blue == 128)
                result.add(CombatAutomationRules.Opening.RED);
            else if(red == 128 && green == 192 && blue == 255)
                result.add(CombatAutomationRules.Opening.BLUE);
        }

        /* English-name fallback for older resource descriptions without markup. */
        if(result.isEmpty()) {
            String lower = paginaText.toLowerCase(Locale.ROOT);
            int start = lower.indexOf("reduces:");
            if(start >= 0) {
                int end = lower.indexOf("cooldown:", start);
                String reductions = lower.substring(start, end < 0 ? lower.length() : end);
                if(reductions.contains("striking") || reductions.contains("off balance"))
                    result.add(CombatAutomationRules.Opening.GREEN);
                if(reductions.contains("sweeping") || reductions.contains("reeling"))
                    result.add(CombatAutomationRules.Opening.YELLOW);
                if(reductions.contains("oppressive") || reductions.contains("cornered"))
                    result.add(CombatAutomationRules.Opening.RED);
                if(reductions.contains("backhanded") || reductions.contains("dizzy"))
                    result.add(CombatAutomationRules.Opening.BLUE);
            }
        }
        return(result);
    }
}
