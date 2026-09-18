package thunder;

import haven.*;

import java.awt.Color;
import java.awt.image.BufferedImage;

/** In-world item icon and quality label for persistent mining-quality markers. */
public final class TileQualityMarkerSprite extends Sprite {
    private static final Coord ICON_SIZE = new Coord(24, 24);
    private static final int GAP = 3;

    static final class Visual {
        final String materialName;
        final String resourceName;
        final String qualityText;

        Visual(String materialName, String resourceName, String qualityText) {
            this.materialName = materialName;
            this.resourceName = resourceName;
            this.qualityText = qualityText;
        }
    }

    private TileQualityMarkerSprite(Owner owner, Visual visual) {
        super(owner, null);
        BufferedImage icon = loadIcon(visual.resourceName);
        BufferedImage quality = Text.renderstroked(visual.qualityText, Color.WHITE, Color.BLACK).img;
        BufferedImage display = icon == null
            ? quality
            : ItemInfo.catimgsh(UI.scale(GAP), icon, quality);
        setTex2d(new TexI(display));
        tex2dAlign = new Pair<>(0.5, 1.0);
        up2d(4);
    }

    /** Installs the specialized drawable and returns false for ordinary player markers. */
    public static boolean apply(Gob gob, MapFile.PMarker marker) {
        Visual visual = parse(marker == null ? null : marker.nm);
        if(gob == null || visual == null) {return false;}
        gob.setattr(new SprDrawable(gob, owner -> new TileQualityMarkerSprite(owner, visual)));
        return true;
    }

    static Visual parse(String markerName) {
        if(markerName == null || !markerName.startsWith(TileQuality.MARKER_PREFIX)) {return null;}
        int qualityAt = markerName.lastIndexOf(" q");
        if(qualityAt <= TileQuality.MARKER_PREFIX.length() || qualityAt + 2 >= markerName.length()) {
            return null;
        }
        String material = markerName.substring(TileQuality.MARKER_PREFIX.length(), qualityAt).trim();
        String quality = markerName.substring(qualityAt + 2).trim();
        try {
            double parsed = Double.parseDouble(quality);
            if(!Double.isFinite(parsed) || parsed <= 0) {return null;}
        } catch(NumberFormatException e) {
            return null;
        }
        MiningQualityCatalog.Entry entry = MiningQualityCatalog.byDisplayName(material);
        String resourceName = entry == null ? null : entry.resourceName;
        return new Visual(material, resourceName, quality);
    }

    private static BufferedImage loadIcon(String resourceName) {
        if(resourceName == null) {return null;}
        try {
            Resource.Image layer = Resource.remote().load(resourceName).get().layer(Resource.imgc);
            if(layer == null || layer.img == null) {return null;}
            return PUtils.convolvedown(layer.img, UI.scale(ICON_SIZE), CharWnd.iconfilter);
        } catch(Loading loading) {
            throw loading;
        } catch(RuntimeException ignored) {
            // Keep the quality number visible if an unexpected future item has
            // no directly renderable inventory-image layer.
            return null;
        }
    }

    @Override
    public void dispose() {
        synchronized(texLock) {
            if(tex2d != null) {
                tex2d.dispose();
                tex2d = null;
            }
        }
    }
}
