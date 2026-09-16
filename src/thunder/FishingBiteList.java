package thunder;

import haven.Button;
import haven.Label;
import haven.Widget;
import haven.Window;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed parser for the server {@code "This is bait"} window.
 *
 * <p>No live proto dump is in-tree. Layout is locked to Kami's
 * {@code FishingBot.returnFishWindow} (commit {@code 1dd288232}): direct
 * children, skip until the first {@link Button}, then each row is that
 * button followed by labels. Percents in label order are bite (left),
 * land (right), optional combined. Kami named the first two "gear/lure";
 * W16 consensus is bite then land. Green is {@link Label} {@code col}
 * (button {@code ch} color is not readable after render).
 *
 * <p>If any started row lacks a name and two percents, the whole parse
 * fails and the overlay must not tint.
 */
public final class FishingBiteList {
    public static final String CAPTION = "This is bait";
    private static final Pattern PCT = Pattern.compile("(\\d{1,3})\\s*%");

    private FishingBiteList() {}

    public static final class Token {
	public enum Kind { BUTTON, LABEL }

	public final Kind kind;
	public final String text;
	public final boolean green;
	public final Label label;
	public final Button button;

	public Token(Kind kind, String text, boolean green, Label label) {
	    this.kind = kind;
	    this.text = text == null ? "" : text;
	    this.green = green;
	    this.label = label;
	    this.button = null;
	}

	private Token(Button button) {
	    this.kind = Kind.BUTTON; this.text = button == null || button.text == null ? "" : button.text.text;
	    this.green = false; this.label = null; this.button = button;
	}

	public static Token button(String text) {
	    return new Token(Kind.BUTTON, text, false, null);
	}
	public static Token button(Button button) {return new Token(button);}

	public static Token label(String text) {
	    return label(text, false);
	}

	public static Token label(String text, boolean green) {
	    return new Token(Kind.LABEL, text, green, null);
	}

	public static Token label(String text, boolean green, Label src) {
	    return new Token(Kind.LABEL, text, green, src);
	}
    }

    public static final class Row {
	public final String name;
	public final int bitePct;
	public final int landPct;
	public final Integer combinedPct;
	public final boolean green;
	public final Label nameLbl;
	public final Label biteLbl;
	public final Label landLbl;
	public final Button button;

	public Row(String name, int bitePct, int landPct, Integer combinedPct, boolean green) {
	    this(name, bitePct, landPct, combinedPct, green, null, null, null, null);
	}

	public Row(String name, int bitePct, int landPct, Integer combinedPct, boolean green,
		   Label nameLbl, Label biteLbl, Label landLbl) {
	    this(name, bitePct, landPct, combinedPct, green, nameLbl, biteLbl, landLbl, null);
	}

	public Row(String name, int bitePct, int landPct, Integer combinedPct, boolean green,
		   Label nameLbl, Label biteLbl, Label landLbl, Button button) {
	    this.name = name;
	    this.bitePct = bitePct;
	    this.landPct = landPct;
	    this.combinedPct = combinedPct;
	    this.green = green;
	    this.nameLbl = nameLbl;
	    this.biteLbl = biteLbl;
	    this.landLbl = landLbl;
	    this.button = button;
	}
    }

    public static final class Result {
	public final boolean parsed;
	public final List<Row> rows;

	private Result(boolean parsed, List<Row> rows) {
	    this.parsed = parsed;
	    this.rows = Collections.unmodifiableList(rows);
	}

	public static Result fail() {
	    return new Result(false, Collections.emptyList());
	}

	public static Result ok(List<Row> rows) {
	    return new Result(true, new ArrayList<>(rows));
	}

	public Row find(String species) {
	    if(species == null || species.isEmpty() || !parsed) {return null;}
	    String n = FishingTackle.norm(species);
	    for(Row r : rows) {
		if(FishingTackle.norm(r.name).equals(n)) {return r;}
	    }
	    return null;
	}
    }

    public static boolean isGreen(Color c) {
	if(c == null) {return false;}
	int r = c.getRed(), g = c.getGreen(), b = c.getBlue();
	return g >= 160 && g > r + 30 && g > b + 30;
    }

    public static Integer parsePct(String t) {
	if(t == null) {return null;}
	Matcher m = PCT.matcher(t.trim());
	if(!m.find()) {return null;}
	int v = Integer.parseInt(m.group(1));
	if(v > 100) {return null;}
	return v;
    }

    public static Result parse(List<Token> tokens) {
	if(tokens == null || tokens.isEmpty()) {return Result.fail();}
	List<Row> rows = new ArrayList<>();
	int i = 0;
	while(i < tokens.size() && tokens.get(i).kind != Token.Kind.BUTTON) {i++;}
	if(i >= tokens.size()) {return Result.fail();}
	while(i < tokens.size()) {
	    if(tokens.get(i).kind != Token.Kind.BUTTON) {
		i++;
		continue;
	    }
	    boolean rowGreen = tokens.get(i).green;
	    Button rowButton = tokens.get(i).button;
	    i++;
	    String name = null;
	    Label nameLbl = null;
	    List<Integer> pctVals = new ArrayList<>();
	    List<Label> pctLbls = new ArrayList<>();
	    int labels = 0;
	    while(i < tokens.size() && tokens.get(i).kind == Token.Kind.LABEL) {
		Token t = tokens.get(i++);
		labels++;
		rowGreen |= t.green;
		Integer pct = parsePct(t.text);
		if(pct != null) {
		    pctVals.add(pct);
		    pctLbls.add(t.label);
		} else if(name == null) {
		    String trimmed = t.text.trim();
		    if(!trimmed.isEmpty()) {
			name = trimmed;
			nameLbl = t.label;
		    }
		}
	    }
	    if(labels == 0) {continue;}
	    if(name == null || pctVals.size() < 2) {return Result.fail();}
	    Integer combined = pctVals.size() >= 3 ? pctVals.get(2) : null;
	    rows.add(new Row(name, pctVals.get(0), pctVals.get(1), combined, rowGreen,
			     nameLbl, pctLbls.get(0), pctLbls.get(1), rowButton));
	}
	if(rows.isEmpty()) {return Result.fail();}
	return Result.ok(rows);
    }

    public static List<Token> tokensFrom(Window wnd) {
	List<Token> out = new ArrayList<>();
	if(wnd == null) {return out;}
	for(Widget w : wnd.children()) {
	    if(w instanceof Button) {
		Button b = (Button) w;
		String t = b.text != null ? b.text.text : "";
		out.add(Token.button(b));
	    } else if(w instanceof Label) {
		Label l = (Label) w;
		String t = l.original != null ? l.original : l.texts;
		out.add(Token.label(t, isGreen(l.col), l));
	    }
	}
	return out;
    }

    public static Result parse(Window wnd) {
	return parse(tokensFrom(wnd));
    }
}
