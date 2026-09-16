# Local Pathfinder test scenarios

`PfTestRunner` exposes only allowlisted localhost scenarios through
`DevControl` (`POST /pf/run?scenario=...`). It does not accept arbitrary remote
movement commands.

Retained scenarios cover:

- local scene and occupancy observation;
- open-ground and obstacle/corridor selection;
- boulder and waterline approach selection;
- local land movement;
- hitbox-aware object interaction;
- local replay and diagnostics.

World routing, named places, automatic transitions, exploration routing,
streaming campaigns, and Critical Routes are intentionally not registered.

```text
GET  /pf/scenarios
POST /pf/run?scenario=observe
GET  /pf/result?run_id=
POST /pf/cancel
```

Launch with `tools/launch-pf-test.sh` after `ant bin`. Completed runs write
diagnostic artifacts below `dev-snapshots/pf/tests/<scenario>/`; those runtime
artifacts are not committed.

Offline replay remains available:

```bash
java -cp bin/hafen.jar:bin/HavenNavigationCore.jar \
  haven.pathfinding.NavReplayRunner path/to/run.navreplay.jsonl
```

The debug overlay (`:pf debug`) shows local occupancy, exact geometry, raw and
smoothed routes, the active corner, confirmed position, and the latest local
failure reason.
