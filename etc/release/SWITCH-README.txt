Switching from official Thunder to this experimental fork
========================================================

This zip is only the updater + launchers. It is not the full game.

If you already have a Thunder folder from github.com/onefuncman/thunder
(you run Thunder.bat or thunder.sh):

  1. Close the client.
  2. Copy updater.jar from this zip into that folder, replacing the old one.
     Also copy Thunder.bat / thunder.sh / run.bat / run if you do not already
     have them.
  3. Launch as usual (Thunder.bat on Windows, thunder.sh or ./run on Linux).
  4. The updater will pull this fork's latest GitHub release, then start the
     game. That can take a minute the first time.

If you only downloaded hafen.jar or client-res.jar:

  Those files are not a playable install. They have no updater and no
  launcher. Download a full package instead:

    Windows:     Thunder-windows-x64.zip  (Java is bundled; run Thunder.bat)
    Linux/macOS: Thunder-cross-platform.zip  (needs Java 17+; run thunder.sh)

  Latest experimental builds:
    https://github.com/SolomonIbnDavid/thunder/releases

Steam Workshop installs cannot use this updater. Use a standalone zip.
