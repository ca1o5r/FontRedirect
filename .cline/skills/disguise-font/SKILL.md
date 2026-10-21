---
name: disguise-font
description: Disguise a source font file as one or more target font files by replicating all font metadata (name table, PostScript name, family, weight/style flags). Handles TTF, OTF (with real CFF conversion) and TTC/OTC collections with per-face identity and shared-table optimization. Use when the user wants to disguise, clone, replicate, or swap font metadata, replace font identities, or create font copies with different names.
---

# Disguise Font

Replace all identifying metadata of a source font to match target fonts. Glyph outlines come from the source; the name table, weight/style flags and (for `.otf` targets) the outline format are rebuilt to match each target.

## Prerequisites

- Python 3 with `fontTools` installed (`pip install fonttools`)
- Optional: FontForge (for metadata verification)

## Workflow

### Step 1: Identify source and targets

Ask the user (or infer from context):
- **Source font**: the font whose glyphs will be used
- **Target fonts**: the fonts whose metadata should be replicated

### Step 2: Determine target scope

- If targets are **specific files**: use them directly
- If targets are **all fonts in a directory**: scan for font files (.ttf, .ttc, .otf)
- Optionally filter by file size (e.g., < 3MB, > 3MB)

### Step 3: Run the disguise script

```bash
python <skill_dir>/scripts/disguise_font.py [--otf-mode convert|rename|keep] <source.ttf> <target1> [target2 ...]
```

The script will:
1. Read metadata from each target font (all faces inside TTC files, including each face's own weight/bold/italic fields)
2. Copy the source font with modified metadata to match each target
3. For TTC files: build a collection where byte-identical tables are shared, while per-face tables (`name`, `OS/2`, `head`) stay independent
4. Replace each target file in-place

`--otf-mode` controls what happens when a `.otf` target receives TrueType (glyf) outlines:

| mode | behaviour |
| --- | --- |
| `convert` (default) | convert outlines to cubic and write a real CFF/OTF (`OTTO`) |
| `rename` | keep TrueType data but write `<stem>.ttf` (the `.otf` is removed) |
| `keep` | legacy: TrueType data stays inside the `.otf` file |

### Step 4: Verify

The script prints a summary of all fonts processed. Optionally verify with FontForge:

```bash
fontforge -lang=py -c "
import fontforge
f = fontforge.open('target.ttf')
print(f.fontname, f.familyname, f.fullname)
for r in f.sfnt_names: print(f'  {r[1]} = {r[2]}')
f.close()
"
```

## Key Details

### Metadata replaced
- The whole `name` table is replicated verbatim from each target face: every platform (Windows/Mac), every language and every name ID, including typographic family/subfamily (16/17), description (10) and localized records
- `OS/2.usWeightClass`, `OS/2.fsSelection` and `head.macStyle` are copied from the target face
- Nothing from the source font's identity survives in the `name` table

### TTC handling
- Reads the TTC header to determine font count
- Extracts metadata from every face, including its own `OS/2` weight/`fsSelection` and `head.macStyle`
- Builds each face independently, then shares every table that is byte-identical (glyf, cmap, layout, ...)
- `name`, `OS/2` and `head` are stored per face whenever they differ, so Regular+Bold collections keep correct weights and style flags
- Keeps output size close to the source font

### Weight and style flags
- Copies the target's own `OS/2.usWeightClass`, `fsSelection` (ITALIC/BOLD/REGULAR) and `head.macStyle`
- Falls back to parsing the SubFamily when a target has no `OS/2`: Thin/Hairline 100, ExtraLight 200, Light 300, Regular 400, Medium 500, SemiBold 600, Bold 700, ExtraBold 800, Black/Heavy 900

### OTF output
- With `--otf-mode convert` (default) a `.otf` target gets real CFF outlines: quadratic curves become cubic, while cmap, metrics, name/OS2 and GSUB/GPOS/GDEF are preserved
- TrueType hinting (`cvt `, `fpgm`, `prep`, `gasp`) cannot be represented in CFF and is dropped
- Collections always keep the source outline format, so a glyf source produces a glyf `ttcf` even for an `.otc` target

## Common Metadata Pattern

When targets share identical metadata except for identifying names (common for fonts from the same foundry), the script preserves each target's exact metadata values.
