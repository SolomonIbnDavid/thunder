# Tile Quality Tracker

Thunder records the highest observed quality for mining, digging, and water-filling actions at the map tile that produced it. Mining observations can also create permanent flags for important stone, ore, and gemstones.

## Mining quality markers

Open the world map and press **Q**, then press **Marker settings**.

- The catalog contains 52 stone types, 18 ore types, and 12 gemstone types.
- **Any stone or ore** is a universal minimum-quality rule. Every stone or ore at or above that quality is flagged, regardless of its individual setting; `0`/`Off` disables the universal rule.
- Each stone and ore has an independent minimum quality. `0`/`Off` disables automatic flags for that material.
- Gemstones are always important and are flagged at every quality; no gemstone threshold is required.
- Threshold comparison is inclusive. A quality 50.0 Granite observation qualifies when Granite is set to 50.
- Threshold settings persist in `config.json` and apply to every character/map.
- **Copy settings** puts the universal rule and all individual rules in a versioned JSON profile on the clipboard. **Paste settings** replaces the local threshold profile with the shared one.

When an observation qualifies, Thunder creates a purple player marker named like `[TQ] Granite q72.5`. It has **Display in world** enabled, so the same persistent flag is visible over the in-game tile and on the map. Raising or disabling a threshold does not erase flags that were already created; they remain ordinary editable player markers and can be removed from the map marker list.

Purple is an existing player-marker group color. If purple marker upload is enabled in Thunder's automapper settings, quality flags are eligible for the normal remote marker upload path as well.

## Sharing

Normal `.hmap` export/import now includes three related pieces:

1. map grids;
2. `[TQ]` player flags; and
3. the raw per-tile quality observations used by the quality overlay.

This means another Thunder client importing the map can use the overlay and search window, not merely see the flags. Imports merge by maximum quality, so shared data cannot lower a better local observation. Older clients ignore the new `tilequality` export layer and still import the ordinary map and marker records.

Threshold profiles are shared separately with the **Copy settings** and **Paste settings** buttons. Importing someone else's map does not silently replace personal thresholds.

## Search and overlay

The map's **Q** button opens `TileQualityWnd`.

- **Show overlay** renders recorded tile qualities on the saved map.
- Selecting a material limits the overlay and result list to that material.
- **Current segment only** restricts results to the connected saved-map segment containing the player.
- Results can be sorted by quality or same-segment tile distance.
- Clicking a result centers the map on that tile.
- An asterisk in the result list means that observation currently satisfies the important-material policy.

The overlay uses the highest selected/available quality on each tile and the existing gray → white → green → blue → purple → orange → red palette.

## Catalog and stable keys

Mining records use stable category keys:

- `stone/granite`
- `ore/black-ore`
- `gem/sapphire`

The displayed item name is authoritative for classification. Resource aliases handle ores whose resource slug differs from the game name, such as `magnetite` → Black Ore and `petzite` → Direvein. Version-2 tracker data is normalized on load, including old `stone/<resource>` ore keys and bare gem keys such as `ruby`.

The gemstone catalog is Amber, Amethyst, Diamond, Emerald, Jade, Moonstone, Onyx, Opal, Ruby, Sapphire, Topaz, and Turquoise. The item resource is dynamic, so Thunder extracts the type from names such as `Fair Smooth Onyx`. An unknown future gemstone name still receives a `gem/<name>` key and the always-important policy.

`MiningQualityCatalog` is also the source for Miner Bot's stone-versus-ore classification, keeping support-building material logic and quality-marker logic aligned.

## Persistence model

The tracker uses sparse per-grid storage alongside the map file:

| Key | Contents |
|---|---|
| `thunder-tq-index` | grid IDs containing observations |
| `thunder-tq-grid-%x` | sparse tile/material/quality entries for one grid |

Quality is stored as quality ×10 in a signed short (`47.3` → `473`). A tile can contain multiple observations, such as its base stone plus a gemstone or Strange Crystal. Only a higher observation replaces a stored value.

Grid payload version 3 stores canonical string keys. Version 2 remains readable and is migrated in memory. Data is loaded lazily through `MapFile.sstore()` and compressed with `ZMessage`.

## Capture behavior

### Mining

An area-mine click arms the mine action. Each `gfx/terobjs/mineout` overlay advances the pending location to the wall tile that just opened. Inventory item-info updates then provide the material name and quality. This works for manual mining and Miner Bot V3 because both use Thunder's normal mining action.

Only items in the main inventory are accepted for mining/digging attribution. Stacked item names are normalized, and delayed item information is retried until name and quality are available.

### Digging

The `gfx/hud/curs/dig` cursor plus a normal map click arms a dig action. The player's tile at item arrival is used because digging produces from under the character. Soil, Sand, Clay, and named clay variants are recorded; unrelated side products such as Earthworms are rejected.

### Water

Using a carried vessel on fresh water, salt water, wells, or wellsprings arms a five-second fill action. The filled hand item's content quality is attributed to the source tile. Fresh, spring, and salt water remain separate keys.

## Diagnostics

`dev.tq` and `debug.tile_quality` expose the pending action, cursor changes, classification results, and record events. The existing debug dump/snapshot/clear commands remain available for diagnosing attribution problems.
