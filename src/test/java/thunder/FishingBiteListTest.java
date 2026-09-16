package thunder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FishingBiteListTest {
    private static List<FishingBiteList.Token> kamiRow(String name, int bite, int land, Integer combined, boolean green) {
	List<FishingBiteList.Token> t = new ArrayList<>();
	t.add(FishingBiteList.Token.button(""));
	t.add(FishingBiteList.Token.label(name, green));
	t.add(FishingBiteList.Token.label(bite + "%"));
	t.add(FishingBiteList.Token.label(""));
	t.add(FishingBiteList.Token.label(land + "%"));
	t.add(FishingBiteList.Token.label(""));
	if(combined != null) {t.add(FishingBiteList.Token.label(combined + "%"));}
	return t;
    }

    @Test
    void kamiLayoutTwoRows() {
	List<FishingBiteList.Token> t = new ArrayList<>();
	t.add(FishingBiteList.Token.label("deco"));
	t.addAll(kamiRow("Pike", 86, 100, 86, false));
	t.addAll(kamiRow("Roach", 40, 55, 22, true));
	FishingBiteList.Result r = FishingBiteList.parse(t);
	assertTrue(r.parsed);
	assertEquals(2, r.rows.size());
	assertEquals("Pike", r.rows.get(0).name);
	assertEquals(86, r.rows.get(0).bitePct);
	assertEquals(100, r.rows.get(0).landPct);
	assertEquals(Integer.valueOf(86), r.rows.get(0).combinedPct);
	assertFalse(r.rows.get(0).green);
	assertEquals("Roach", r.rows.get(1).name);
	assertEquals(55, r.rows.get(1).landPct);
	assertTrue(r.rows.get(1).green);
    }

    @Test
    void missingPercentsFailClosed() {
	List<FishingBiteList.Token> t = new ArrayList<>();
	t.add(FishingBiteList.Token.button(""));
	t.add(FishingBiteList.Token.label("Pike"));
	t.add(FishingBiteList.Token.label("86%"));
	FishingBiteList.Result r = FishingBiteList.parse(t);
	assertFalse(r.parsed);
	assertTrue(r.rows.isEmpty());
    }

    @Test
    void noButtonsFailClosed() {
	List<FishingBiteList.Token> t = new ArrayList<>();
	t.add(FishingBiteList.Token.label("Pike"));
	t.add(FishingBiteList.Token.label("86%"));
	t.add(FishingBiteList.Token.label("100%"));
	assertFalse(FishingBiteList.parse(t).parsed);
    }

    @Test
    void strayButtonWithoutLabelsIsIgnored() {
	List<FishingBiteList.Token> t = new ArrayList<>();
	t.addAll(kamiRow("Pike", 86, 100, 86, false));
	t.add(FishingBiteList.Token.button("close"));
	FishingBiteList.Result r = FishingBiteList.parse(t);
	assertTrue(r.parsed);
	assertEquals(1, r.rows.size());
    }

    @Test
    void findIsCaseInsensitive() {
	List<FishingBiteList.Token> t = kamiRow("Silver Bream", 10, 100, null, false);
	assertNotNull(FishingBiteList.parse(t).find("silver bream"));
    }

    @Test
    void parsePct() {
	assertEquals(Integer.valueOf(86), FishingBiteList.parsePct("86%"));
	assertEquals(Integer.valueOf(0), FishingBiteList.parsePct("0 %"));
	assertNull(FishingBiteList.parsePct("Pike"));
	assertNull(FishingBiteList.parsePct("140%"));
    }
}
