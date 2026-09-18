package thunder;

import haven.*;
import haven.proto.ClipboardUtil;

import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.util.*;

/** Threshold editor for permanent stone/ore flags. Gemstones are always flagged. */
public final class TileQualitySettingsWnd extends WindowX {
    private static final int ROWH = UI.scale(22);
    private static final Color ROW_EVEN = new Color(255, 255, 255, 12);
    private static final Color ROW_ODD = new Color(255, 255, 255, 25);

    private final MaterialList materials;
    private final TextEntry search;
    private final TextEntry anyRockThreshold;
    private final TextEntry threshold;
    private final Button save;
    private final Label selectedLabel;
    private MiningQualityCatalog.Entry selected;

    public TileQualitySettingsWnd() {
        super(Coord.z, "Mining Quality Markers");
        justclose = true;

        int y = 0;
        add(new Label("Stone and ore: set the minimum quality to flag (0 = off)."), 0, y);
        y += UI.scale(20);
        add(new Label("Gemstones are always recorded and flagged, at every quality."), 0, y);
        y += UI.scale(24);

        add(new Label("Any stone or ore:"), 0, y + UI.scale(3));
        anyRockThreshold = add(new TextEntry(UI.scale(70),
            TileQualityThresholds.formatQuality(TileQualityThresholds.anyRock())) {
            @Override public void activate(String text) {saveAnyRockThreshold();}
        }, UI.scale(145), y);
        add(new Button(UI.scale(125), "Save universal", this::saveAnyRockThreshold), UI.scale(225), y);
        y += anyRockThreshold.sz.y + UI.scale(2);
        add(new Label("Flags every mined rock at or above this quality (0 = off)."), 0, y);
        y += UI.scale(24);

        materials = new MaterialList(UI.scale(430), 15);
        add(new Label("Search:"), 0, y + UI.scale(3));
        search = add(new TextEntry(UI.scale(300), "") {
            @Override protected void changed() {
                super.changed();
                materials.filter(text());
            }
        }, UI.scale(55), y);
        y += search.sz.y + UI.scale(6);

        add(new Label("Type"), UI.scale(4), y);
        add(new Label("Material"), UI.scale(80), y);
        add(new Label("Flag at quality"), UI.scale(310), y);
        y += UI.scale(18);
        add(materials, 0, y);
        materials.setItems(MiningQualityCatalog.entries());
        y += materials.sz.y + UI.scale(8);

        selectedLabel = add(new Label("Select a material"), 0, y + UI.scale(3));
        threshold = add(new TextEntry(UI.scale(70), "0") {
            @Override public void activate(String text) {saveThreshold();}
        }, UI.scale(245), y);
        save = add(new Button(UI.scale(105), "Save threshold", this::saveThreshold), UI.scale(325), y);
        threshold.setcanfocus(false);
        save.disable(true);
        y += threshold.sz.y + UI.scale(8);

        add(new Button(UI.scale(125), "Copy settings", this::copySettings), 0, y);
        add(new Button(UI.scale(125), "Paste settings", this::pasteSettings), UI.scale(135), y);
        y += UI.scale(28);
        add(new Label("Map export/import (.hmap) shares recorded tile overlays and flags."), 0, y);
        pack();
    }

    public static void toggle(UI ui) {
        if(ui == null || ui.gui == null) {return;}
        if(ui.gui.tileQualitySettingsWnd == null) {
            ui.gui.tileQualitySettingsWnd = ui.gui.add(new TileQualitySettingsWnd(), UI.scale(170), UI.scale(120));
        } else {
            ui.gui.tileQualitySettingsWnd.destroy();
        }
    }

    @Override
    public void destroy() {
        if(ui != null && ui.gui != null && ui.gui.tileQualitySettingsWnd == this) {
            ui.gui.tileQualitySettingsWnd = null;
        }
        super.destroy();
    }

    private void select(MiningQualityCatalog.Entry entry) {
        selected = entry;
        if(entry == null) {
            selectedLabel.settext("Select a material");
            threshold.settext("0");
            threshold.setcanfocus(false);
            save.disable(true);
            return;
        }
        selectedLabel.settext(entry.name + ":");
        boolean gem = entry.category == MiningQualityCatalog.Category.GEM;
        threshold.settext(gem ? "Always" : TileQualityThresholds.formatQuality(TileQualityThresholds.get(entry.key)));
        threshold.setcanfocus(!gem);
        save.disable(gem);
    }

    private void saveThreshold() {
        if(selected == null || selected.category == MiningQualityCatalog.Category.GEM) {return;}
        try {
            int value = TileQualityThresholds.parseQuality(threshold.text().replace("Off", "0"));
            TileQualityThresholds.set(selected.key, value);
            threshold.settext(TileQualityThresholds.formatQuality(value));
            ui.gui.msg(selected.name + " quality marker threshold: " + TileQualityThresholds.formatQuality(value), GameUI.MsgType.INFO);
        } catch(RuntimeException e) {
            ui.gui.error("Quality threshold must be a non-negative number.");
        }
    }

    private void saveAnyRockThreshold() {
        try {
            int value = TileQualityThresholds.parseQuality(anyRockThreshold.text().replace("Off", "0"));
            TileQualityThresholds.setAnyRock(value);
            anyRockThreshold.settext(TileQualityThresholds.formatQuality(value));
            ui.gui.msg("Universal rock quality marker threshold: "
                + TileQualityThresholds.formatQuality(value), GameUI.MsgType.INFO);
        } catch(RuntimeException e) {
            ui.gui.error("Universal rock threshold must be a non-negative number.");
        }
    }

    private void copySettings() {
        ClipboardUtil.copy(TileQualityThresholds.exportJson(
            TileQualityThresholds.snapshot(), TileQualityThresholds.anyRock()));
        ui.gui.msg("Mining quality settings copied to the clipboard.", GameUI.MsgType.INFO);
    }

    private void pasteSettings() {
        try {
            Transferable contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if(contents == null || !contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                throw new IllegalArgumentException("Clipboard has no text");
            }
            String json = (String)contents.getTransferData(DataFlavor.stringFlavor);
            TileQualityThresholds.Profile profile = TileQualityThresholds.importProfileJson(json);
            TileQualityThresholds.replace(profile);
            anyRockThreshold.settext(TileQualityThresholds.formatQuality(profile.anyRockQualityX10));
            select(selected);
            ui.gui.msg("Imported " + profile.thresholds.size()
                + " material thresholds and the universal rock threshold.", GameUI.MsgType.INFO);
        } catch(Exception e) {
            ui.gui.error("Could not import mining quality settings: " + e.getMessage());
        }
    }

    private final class MaterialList extends FilteredListBox<MiningQualityCatalog.Entry> {
        MaterialList(int w, int h) {
            super(w, h, ROWH);
            bgcolor = new Color(0, 0, 0, 84);
            showFilterText = false;
        }

        @Override
        protected boolean match(MiningQualityCatalog.Entry entry, String filter) {
            String q = filter.trim().toLowerCase(Locale.ROOT);
            return q.isEmpty()
                || entry.name.toLowerCase(Locale.ROOT).contains(q)
                || entry.category.displayName.toLowerCase(Locale.ROOT).contains(q);
        }

        @Override
        protected void drawitem(GOut g, MiningQualityCatalog.Entry entry, int idx) {
            g.chcolor((idx % 2 == 0) ? ROW_EVEN : ROW_ODD);
            g.frect(Coord.z, g.sz());
            g.chcolor();
            g.atext(entry.category.displayName, new Coord(UI.scale(4), ROWH / 2), 0, 0.5);
            g.atext(entry.name, new Coord(UI.scale(80), ROWH / 2), 0, 0.5);
            String value = entry.category == MiningQualityCatalog.Category.GEM
                ? "Always"
                : TileQualityThresholds.formatQuality(TileQualityThresholds.get(entry.key));
            g.atext(value, new Coord(UI.scale(310), ROWH / 2), 0, 0.5);
        }

        @Override
        public void change(MiningQualityCatalog.Entry entry) {
            super.change(entry);
            select(entry);
        }
    }
}
