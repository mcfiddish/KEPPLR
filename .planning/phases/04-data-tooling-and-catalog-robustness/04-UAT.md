---
status: complete
phase: 04-data-tooling-and-catalog-robustness
source: 04-data-SUMMARY.md
started: 2026-04-26T00:00:00Z
updated: 2026-04-26T00:02:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Gaia source index error message — missing file name
expected: When getStar() is called on a catalog backed by a tile-pack that has no source index, the thrown IllegalArgumentException message names the exact missing file (gaia.sourceidx) and includes the exact command to rebuild it.
result: issue
reported: "GaiaCatalogTest.getStar_throwsClearMessage_whenSourceIndexMissing:29 expected IllegalArgumentException but was StarCatalogLookupException — message content unverifiable due to wrong exception type"
severity: major

### 2. Gaia source index error message — exception type
expected: The exception thrown from getStar() when there is no source index is an IllegalArgumentException (not UnsupportedOperationException). Callers that catch IllegalArgumentException receive the error.
result: issue
reported: "expected: <java.lang.IllegalArgumentException> but was: <kepplr.stars.StarCatalogLookupException>. Also getStar_reportsCorruption_whenIndexFileCorrupt:55 throws IOException 'Invalid source index size (must be multiple of 16): 15' instead of a typed exception."
severity: major

### 3. getCacheStats() returns tile count and memory estimate
expected: Calling getCacheStats() on a GaiaCatalog after loading at least one tile returns a CacheStats record with a non-zero cachedTileCount and a positive estimatedMemoryBytes value (~32 bytes x number of cached GaiaStar objects).
result: pass

### 4. Python pure-function tests pass without Blender
expected: Running `python3 src/test/python/test_convert_pure.py` (or pytest on that file) from the repo root completes with 4 tests passing and no imports of bpy or any Blender module. The tests cover quaternion math, padding, GLB structure validation, and compute_model_to_bodyfixed_quat.
result: pass

## Summary

total: 4
passed: 2
issues: 2
pending: 0
skipped: 0
blocked: 0

## Gaps

- truth: "getStar() on a tile-pack catalog with no source index throws IllegalArgumentException with a message naming gaia.sourceidx and the rebuild command"
  status: failed
  reason: "User reported: GaiaCatalogTest.getStar_throwsClearMessage_whenSourceIndexMissing:29 expected IllegalArgumentException but was StarCatalogLookupException"
  severity: major
  test: 1
  root_cause: "getStar() null-guards on sourceIndexFile (a Path, always non-null) instead of sourceIndexChannel (null when file absent). The binary search runs with sourceIndexEntries=0 and falls through to StarCatalogLookupException."
  artifacts:
    - path: "src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java"
      issue: "getStar():364 checked sourceIndexFile==null instead of sourceIndexChannel==null"
  missing:
    - "Check sourceIndexChannel==null in getStar() — fixed"
  debug_session: ""

- truth: "getStar() with a corrupt source index (non-16-byte-aligned) throws IllegalArgumentException about corruption; load() succeeds"
  status: failed
  reason: "User reported: getStar_reportsCorruption_whenIndexFileCorrupt:55 throws IOException 'Invalid source index size (must be multiple of 16): 15' — load() throws before getStar() is ever called"
  severity: major
  test: 2
  root_cause: "load() throws IOException immediately when source index size is not a multiple of 16. The test expects load() to succeed and getStar() to throw IllegalArgumentException."
  artifacts:
    - path: "src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java"
      issue: "load():287 threw IOException for corrupt index size instead of deferring to getStar()"
  missing:
    - "Defer corrupt-size detection to getStar() using sourceIndexEntries=-1 sentinel — fixed"
  debug_session: ""
