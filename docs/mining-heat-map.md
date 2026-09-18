# Mining Heat Map

The Mining Heat Map is an advisory search tool for locating the local quality
high point of stone, ore, gemstones, Quarryartz, and Strange Crystal. Open it
from **Extended → Mining Heat Map** or assign the **Mining Heat Map** keybind.

## Material selection

**Follow latest** is enabled when the client starts. Each observed mining drop
becomes the active material. Choosing a material from the dropdown disables
Follow latest and locks the heat map to that material, so ordinary stone drops
cannot replace a rare Quarryartz, ore, gem, or Strange Crystal search.

The helper reads the persistent observations already owned by the Tile Quality
Tracker. It considers the selected material on the current loaded map segment
within 50 tiles of the player. It does not create a second quality database.

## Overlay and guidance

Raw samples are labeled with their exact quality. Their colors are normalized
across the local sample range: blue is the local low end, yellow is the middle,
and green is the local high end. A faint inverse-distance surface fills the
space between sufficiently dense samples.

The search uses the same weighted local surface-fit primitive as Fishing
Helper. With one or two observations it requests an exploratory **PROBE**.
With more observations it marks **NEXT** one tile along the rising gradient.
The suggestion is snapped to a loaded, unmined frontier tile next to known
cave floor. It never points to an opened tile or unloaded terrain.

A best observation becomes **HIGHEST** only after lower readings bracket it
from opposing directions. Confidence reflects the amount and spatial spread
of the available quality samples.

## Rare and missing drops

Every mineout is remembered for the login session. If the active material did
not drop from that tile, the overlay draws a hollow tested marker and excludes
the tile from future suggestions. Missing drops are coverage only: they are
never interpreted as quality zero and cannot distort the fitted surface.

Quality observations remain persistent through the Tile Quality Tracker and
normal `.hmap` sharing. No-drop coverage is session-only; after a restart the
terrain itself still prevents already-opened tiles from becoming suggestions.

## Safety boundary

The helper never walks, selects a mining area, mines, or changes equipment. It
only observes the normal mining flow and displays guidance.
