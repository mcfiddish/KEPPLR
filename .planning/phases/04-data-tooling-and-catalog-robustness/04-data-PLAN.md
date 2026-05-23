---
gsd_plan_version: 1.0
phase: 4
plan: 04-data
name: Data Tooling and Catalog Robustness
subsystem: stars,modeling
type: tdd
autonomous: true
wave: 1
depends_on: []
requirements:
  - DATA-01
  - DATA-02
  - DATA-03
objective: Make external star/catalog/model data failures easier to diagnose and safer to evolve.
context:
  - .planning/codebase/ARCHITECTURE.md
  - .planning/codebase/CONCERNS.md
  - ".claude/KEPPLR_Roadmap.md"
success_criteria:
  - Gaia lookup paths report missing optional source indexes clearly and tests cover the behavior.
  - Gaia tile cache cost is measured or bounded with memory-aware criteria, with evidence captured in docs or tests.
  - Pure model-converter parsing and metadata helpers are isolated enough for fixture tests that do not require Blender.
  - Existing CLI/tool behavior remains compatible with current documentation.
tags:
  - data
  - gaia
  - modeling
  - robustness
threat_model: []
---

# Phase 4 Plan: Data Tooling and Catalog Robustness

## Objective

Make external star/catalog/model data failures easier to diagnose and safer to evolve.

## Context

Phase 4 addresses the data tooling concerns from the codebase map:
- Gaia catalog source-index lookup lacks user-friendly error messages
- Gaia tile cache has no memory-oriented bounds or measurements
- Model conversion tools lack pure parsing tests that can run without Blender

## Tasks

### Task 1: Gaia source index error messages

**Requirement:** DATA-01

**Type:** tdd

**Behavior:** GaiaCatalog.getStar() throws a clear message when the source index is missing, telling the user how to rebuild.

**Implementation:** Improve error message in GaiaCatalog.getStar() to include actionable guidance.

**Files to modify:**
- `src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java`

**Verification:** Unit test verifies exception message contains guidance text.

---

### Task 2: Gaia tile cache memory bounds

**Requirement:** DATA-02

**Type:** auto

**Behavior:** Document or measure Gaia tile cache memory behavior. Add tests that capture memory usage patterns.

**Implementation:** Add cache memory measurement utilities or document the cache behavior. Add tests that exercise cache eviction.

**Files to modify:**
- `src/test/java/kepplr/stars/catalogs/gaia/`

**Verification:** Tests run without error, memory behavior documented or measured.

---

### Task 3: Model converter pure parsing tests

**Requirement:** DATA-03

**Type:** tdd

**Behavior:** Extract metadata parsing and GLB injection logic from convert_to_normalized_glb.py into testable units.

**Implementation:** Create Python test module for metadata parsing functions that don't require Blender.

**Files to modify:**
- `src/main/python/apps/convert_to_normalized_glb.py` (refactor helpers)
- `src/test/python/` (add test file)

**Verification:** Tests run without Blender and pass.

---

## Dependencies

None - each task is independent.

## Exclusion

- No changes to existing CLI tool command-line interfaces
- No changes to supported input formats