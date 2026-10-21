#!/usr/bin/env python3
"""
disguise_font.py - Disguise a source font as one or more target fonts.

Usage:
    python disguise_font.py [--otf-mode convert|rename|keep] <source> <target1> [target2 ...]

For each target file:
  - TTF: replaces metadata in-place with source font's glyphs
  - OTF: same, and by default converts glyf outlines to real CFF so the file
         content matches its ".otf" extension (--otf-mode convert)
  - TTC: builds a collection where each face has source glyphs + target metadata;
         tables that are byte-identical are shared, while per-face tables
         (name / OS/2 / head) stay independent so every face keeps its own
         weight class and bold/italic flags
"""
import struct
import math
import os
import re
import sys
import io
import shutil

try:
    from fontTools.ttLib import TTFont, newTable
    from fontTools.ttLib.tables._n_a_m_e import NameRecord
    from fontTools.fontBuilder import FontBuilder
    from fontTools.pens.recordingPen import RecordingPen
    from fontTools.pens.qu2cuPen import Qu2CuPen
    from fontTools.pens.t2CharStringPen import T2CharStringPen
    from fontTools.pens.boundsPen import BoundsPen
except ImportError:
    print("ERROR: fontTools is required. Install with: pip install fonttools")
    sys.exit(1)


# How --otf-mode handles a glyf-based font written to a .otf target:
#   convert - build a real CFF/OTF (OTTO) from the glyf outlines (default)
#   rename  - keep the TrueType data but write it as "<stem>.ttf" (and drop the .otf)
#   keep    - legacy behaviour: glyf bytes stay inside the ".otf" file
OTF_MODES = ('convert', 'rename', 'keep')
RENAME_EXT = {'.otf': '.ttf', '.otc': '.ttc'}

# ── Name table constants ────────────────────────────────────────────
NAME_ID_MAP = {
    'Copyright': 0, 'Family': 1, 'SubFamily': 2, 'UniqueID': 3,
    'Fullname': 4, 'Version': 5, 'PostScriptName': 6, 'Trademark': 7,
    'Manufacturer': 8, 'Designer': 9, 'VendorURL': 11, 'DesignerURL': 12,
    'License': 13, 'LicenseURL': 14,
}
NAME_ID_TO_KEY = {v: k for k, v in NAME_ID_MAP.items()}
MAC_NAME_IDS = {1, 2, 4, 6}  # Family, SubFamily, Fullname, PostScriptName


# ── Metadata extraction ────────────────────────────────────────────
def extract_ttc_metadata(path):
    """Extract metadata from all fonts in a TTC/OTC file by parsing binary."""
    with open(path, 'rb') as f:
        data = f.read()

    tag = data[0:4]
    if tag != b'ttcf':
        return None  # Not a TTC

    num_fonts = struct.unpack('>I', data[8:12])[0]
    offsets = [struct.unpack('>I', data[12 + i * 4:16 + i * 4])[0]
               for i in range(num_fonts)]

    fonts_meta = []
    for fo in offsets:
        meta = _extract_name_table_at(data, fo)
        fonts_meta.append(meta)
    return fonts_meta


def _extract_name_table_at(data, font_offset):
    """Extract name/OS2/head metadata from a font at given offset in binary data."""
    num_tables = struct.unpack('>H', data[font_offset + 4:font_offset + 6])[0]
    table_offsets = {}
    wanted = (b'name', b'OS/2', b'head')
    for t in range(num_tables):
        to = font_offset + 12 + t * 16
        table_tag = data[to:to + 4]
        if table_tag in wanted:
            table_offsets[table_tag] = struct.unpack('>I', data[to + 8:to + 12])[0]

    name_off = table_offsets.get(b'name')
    if name_off is None:
        return {}

    no = name_off
    count = struct.unpack('>H', data[no + 2:no + 4])[0]
    stroff = struct.unpack('>H', data[no + 4:no + 6])[0]

    meta = {}
    name_records = []
    for r in range(count):
        ro = no + 6 + r * 12
        pid = struct.unpack('>H', data[ro:ro + 2])[0]
        eid = struct.unpack('>H', data[ro + 2:ro + 4])[0]
        lid = struct.unpack('>H', data[ro + 4:ro + 6])[0]
        nid = struct.unpack('>H', data[ro + 6:ro + 8])[0]
        ln = struct.unpack('>H', data[ro + 8:ro + 10])[0]
        so = struct.unpack('>H', data[ro + 10:ro + 12])[0]
        raw = data[no + stroff + so:no + stroff + so + ln]
        name_records.append((pid, eid, lid, nid, raw))
        if nid in NAME_ID_TO_KEY and pid == 3 and lid == 0x0409:
            meta[NAME_ID_TO_KEY[nid]] = raw.decode('utf-16-be', errors='replace')
    meta['_NameRecords'] = name_records

    # Copy the face's own weight/bold/italic identity when the target has it,
    # so faces whose weight lives in the family name (e.g. "Inter Light" +
    # "Regular") keep the correct usWeightClass.
    os2_off = table_offsets.get(b'OS/2')
    if os2_off is not None:
        meta['_TargetWeight'] = struct.unpack('>H', data[os2_off + 4:os2_off + 6])[0]
        meta['_TargetFsSelection'] = struct.unpack('>H', data[os2_off + 62:os2_off + 64])[0]

    head_off = table_offsets.get(b'head')
    if head_off is not None:
        meta['_TargetMacStyle'] = struct.unpack('>H', data[head_off + 44:head_off + 46])[0]
    return meta


def extract_ttf_metadata(path):
    """Extract metadata from a single TTF/OTF file using fontTools."""
    font = TTFont(path)
    meta = {}
    if 'name' in font:
        for rec in font['name'].names:
            if rec.platformID == 3 and rec.langID == 0x0409:
                nid = rec.nameID
                if nid in NAME_ID_TO_KEY:
                    meta[NAME_ID_TO_KEY[nid]] = rec.toUnicode()
        meta['_NameRecords'] = [(r.platformID, r.platEncID, r.langID, r.nameID, r.string)
                                for r in font['name'].names]
    # Keep the target's own weight/bold/italic identity for an exact match.
    if 'OS/2' in font:
        meta['_TargetWeight'] = font['OS/2'].usWeightClass
        meta['_TargetFsSelection'] = font['OS/2'].fsSelection
    if 'head' in font:
        meta['_TargetMacStyle'] = font['head'].macStyle
    font.close()
    return meta


def extract_metadata(path):
    """Extract metadata from a font file. Returns list of dicts (one per font in collection)."""
    with open(path, 'rb') as f:
        header = f.read(4)

    if header == b'ttcf':
        result = extract_ttc_metadata(path)
        if result is not None:
            return result

    # Single font file
    return [extract_ttf_metadata(path)]


# ── Metadata helpers ───────────────────────────────────────────────
def _normalized_subfamily(subfamily):
    """Lowercase and strip separators: 'Semi Bold Italic' -> 'semibolditalic'."""
    return re.sub(r'[^a-z0-9]+', '', (subfamily or '').lower())


def subfamily_weight(subfamily):
    """Map a SubFamily string to an OS/2 usWeightClass value."""
    s = _normalized_subfamily(subfamily)
    if 'black' in s or 'heavy' in s:
        return 900
    if 'extrabold' in s or 'ultrabold' in s:
        return 800
    if 'semibold' in s or 'demibold' in s:
        return 600
    if 'extralight' in s or 'ultralight' in s:
        return 200
    if 'thin' in s or 'hairline' in s:
        return 100
    if 'light' in s:
        return 300
    if 'medium' in s:
        return 500
    if 'bold' in s:
        return 700
    return 400


def subfamily_is_italic(subfamily):
    s = _normalized_subfamily(subfamily)
    return 'italic' in s or 'oblique' in s


def preferred_name(name_table, name_id):
    """Windows en-US name string, falling back to any available record."""
    for record in name_table.names:
        if (record.nameID == name_id and record.platformID == 3
                and record.langID == 0x0409):
            return record.toUnicode()
    return name_table.getDebugName(name_id)


def open_source(source_path, font_number=None):
    """Open the source font; TTC sources default to face 0."""
    if font_number is None:
        with open(source_path, 'rb') as f:
            is_ttc = f.read(4) == b'ttcf'
        font_number = 0 if is_ttc else None
    if font_number is None:
        return TTFont(source_path, recalcTimestamp=False)
    return TTFont(source_path, fontNumber=font_number, recalcTimestamp=False)


def apply_target_metadata(font, config):
    """Replace the identity of `font` with the target metadata in `config`.

    The target's name table is replicated verbatim. OS/2 weight class,
    fsSelection and head.macStyle are copied from the target face when
    available (falling back to the SubFamily), so no face inherits bold or
    italic flags from the source font.
    """
    name_table = newTable('name')
    name_table.names = []
    name_records = config.get('_NameRecords')
    if name_records:
        # Replicate the target's name table verbatim (every platform, language
        # and name ID), so typographic and localized names stay intact.
        for pid, eid, lid, nid, raw in name_records:
            record = NameRecord()
            record.platformID = pid
            record.platEncID = eid
            record.langID = lid
            record.nameID = nid
            record.string = raw
            name_table.names.append(record)
    else:
        for name_key, value in config.items():
            name_id = NAME_ID_MAP.get(name_key)
            if name_id is None:
                continue
            name_table.setName(value, name_id, 3, 1, 0x0409)
            if name_id in MAC_NAME_IDS:
                name_table.setName(value, name_id, 1, 0, 0)
    font['name'] = name_table

    subfamily = config.get('SubFamily', 'Regular')
    weight = config.get('_TargetWeight')
    if weight is None:
        weight = subfamily_weight(subfamily)

    if '_TargetFsSelection' in config:
        style_bits = config['_TargetFsSelection'] & (
            (1 << 0) | (1 << 5) | (1 << 6) | (1 << 9))
        italic = bool(style_bits & 0x0001)
        bold = bool(style_bits & 0x0020)
    else:
        italic = subfamily_is_italic(subfamily)
        bold = weight >= 700
        style_bits = 0
        if italic:
            style_bits |= 1 << 0  # ITALIC
        if bold:
            style_bits |= 1 << 5  # BOLD
        if not italic and not bold:
            style_bits |= 1 << 6  # REGULAR

    if 'OS/2' in font:
        os2 = font['OS/2']
        os2.usWeightClass = weight
        fs = os2.fsSelection & ~((1 << 0) | (1 << 5) | (1 << 6) | (1 << 9))
        os2.fsSelection = fs | style_bits

    if 'head' in font:
        mac_style = font['head'].macStyle & ~0x0003
        if '_TargetMacStyle' in config:
            mac_style |= config['_TargetMacStyle'] & 0x0003
        else:
            if bold:
                mac_style |= 0x0001   # bold
            if italic:
                mac_style |= 0x0002   # italic
        font['head'].macStyle = mac_style


# ── glyf -> CFF conversion (real .otf output) ──────────────────────
def glyf_to_cff(font):
    """Convert a glyf-based TTFont into a CFF (OTTO) TTFont.

    Quadratic outlines become cubic (Qu2CuPen); cmap, metrics, name/OS2 and
    layout tables (GSUB/GPOS/GDEF/...) are carried over, so shaping and
    identity stay intact. TrueType hinting (cvt/fpgm/prep/gasp) cannot be
    represented in CFF and is dropped.
    """
    upem = font['head'].unitsPerEm
    glyph_order = font.getGlyphOrder()
    glyph_set = font.getGlyphSet()
    hmtx = font['hmtx'].metrics
    max_err = upem / 1000.0

    charstrings = {}
    metrics = {}
    x_min = y_min = 1 << 30
    x_max = y_max = -(1 << 30)

    for glyph_name in glyph_order:
        recording = RecordingPen()
        glyph_set[glyph_name].draw(Qu2CuPen(recording, max_err=max_err))

        bounds_pen = BoundsPen(glyph_set)
        recording.replay(bounds_pen)
        bounds = bounds_pen.bounds
        if bounds:
            x_min = min(x_min, bounds[0])
            y_min = min(y_min, bounds[1])
            x_max = max(x_max, bounds[2])
            y_max = max(y_max, bounds[3])
            lsb = int(round(bounds[0]))
        else:
            lsb = 0

        advance = hmtx.get(glyph_name, (upem, 0))[0]
        metrics[glyph_name] = (advance, lsb)

        pen = T2CharStringPen(advance, glyph_set)
        recording.replay(pen)
        charstrings[glyph_name] = pen.getCharString()

    name_table = font['name']
    family = preferred_name(name_table, 1) or ''
    subfamily = preferred_name(name_table, 2) or 'Regular'
    full_name = preferred_name(name_table, 4) or family
    version_text = preferred_name(name_table, 5) or ''
    version_match = re.search(r'\d+(?:\.\d+)?', version_text)
    version = version_match.group(0) if version_match else '1.0'
    ps_name = preferred_name(name_table, 6) or full_name.replace(' ', '')
    copyright_text = preferred_name(name_table, 0) or ''
    head = font['head']
    hhea = font['hhea']
    post = font['post'] if 'post' in font else None


    fb = FontBuilder(upem, isTTF=False)
    fb.setupGlyphOrder(glyph_order)
    fb.setupHorizontalMetrics(metrics)
    fb.setupHorizontalHeader(
        ascent=hhea.ascent, descent=hhea.descent, lineGap=hhea.lineGap,
        advanceWidthMax=hhea.advanceWidthMax,
        minLeftSideBearing=hhea.minLeftSideBearing,
        minRightSideBearing=hhea.minRightSideBearing,
        xMaxExtent=hhea.xMaxExtent,
        caretSlopeRise=hhea.caretSlopeRise,
        caretSlopeRun=hhea.caretSlopeRun,
        caretOffset=hhea.caretOffset,
    )
    fb.setupHead(
        unitsPerEm=upem,
        fontRevision=head.fontRevision,
        flags=head.flags,
        created=head.created,
        modified=head.modified,
        xMin=int(x_min), yMin=int(y_min), xMax=int(x_max), yMax=int(y_max),
        macStyle=head.macStyle,
        lowestRecPPEM=head.lowestRecPPEM,
        indexToLocFormat=0,
        glyphDataFormat=0,
    )
    fb.setupPost(
        keepGlyphNames=False,
        italicAngle=post.italicAngle if post else 0,
        underlinePosition=post.underlinePosition if post else 0,
        underlineThickness=post.underlineThickness if post else 0,
        isFixedPitch=post.isFixedPitch if post else 0,
    )

    font_info = {
        'FullName': full_name,
        'FamilyName': family,
        'Weight': subfamily,
        'version': version,
        'Notice': copyright_text,
        'Copyright': copyright_text,
        'isFixedPitch': post.isFixedPitch if post else 0,
        'ItalicAngle': post.italicAngle if post else 0,
        'UnderlinePosition': post.underlinePosition if post else 0,
        'UnderlineThickness': post.underlineThickness if post else 0,
        'FontBBox': [int(x_min), int(y_min), int(x_max), int(y_max)],
    }
    fb.setupCFF(ps_name, font_info, charstrings, {})

    fb.font['name'] = font['name']
    if 'OS/2' in font:
        fb.font['OS/2'] = font['OS/2']
    # Tables that do not depend on the outline format are copied verbatim.
    for tag in ('cmap', 'vhea', 'vmtx', 'VORG', 'GSUB', 'GPOS', 'GDEF',
                'BASE', 'JSTF', 'MATH', 'kern', 'meta'):
        if tag in font:
            fb.font[tag] = font[tag]
    return fb.font


def save_bytes(font):
    buf = io.BytesIO()
    font.save(buf)
    return buf.getvalue()


def serialize_disguised(source_path, config, convert_to_otf=False,
                        font_number=None):
    """Return the disguised font as bytes, optionally as real CFF/OTF."""
    font = open_source(source_path, font_number)
    try:
        apply_target_metadata(font, config)
        if convert_to_otf and 'glyf' in font:
            converted = glyf_to_cff(font)
            try:
                return save_bytes(converted)
            finally:
                converted.close()
        return save_bytes(font)
    finally:
        font.close()


def set_name_table_ff(source_path, config, font_number=None,
                      convert_to_otf=False):
    """Backwards-compatible wrapper: disguised single font as bytes."""
    return serialize_disguised(source_path, config,
                               convert_to_otf=convert_to_otf,
                               font_number=font_number)


# ── TTC binary builder with shared tables ──────────────────────────
def parse_ttf_tables(ttf_bytes):
    """Parse a TTF binary into ordered dict of {tag: (checksum, data)}."""
    num_tables = struct.unpack('>H', ttf_bytes[4:6])[0]
    tables = {}
    for i in range(num_tables):
        off = 12 + i * 16
        tag = ttf_bytes[off:off + 4]
        checksum = struct.unpack('>I', ttf_bytes[off + 4:off + 8])[0]
        offset = struct.unpack('>I', ttf_bytes[off + 8:off + 12])[0]
        length = struct.unpack('>I', ttf_bytes[off + 12:off + 16])[0]
        tables[tag] = (checksum, ttf_bytes[offset:offset + length])
    return tables


def build_ttc_with_shared_tables(source_path, configs, output_path):
    """Build a TTC where faces share only tables that are byte-identical.

    Each face is generated independently first, so per-face tables such as
    'name', 'OS/2' (weight class) and 'head' (macStyle) stay independent,
    while identical tables (glyf, cmap, layout, ...) are stored once.
    """
    n = len(configs)
    face_binaries = [set_name_table_ff(source_path, cfg) for cfg in configs]
    sfnt_versions = {b[:4] for b in face_binaries}
    if len(sfnt_versions) != 1:
        raise RuntimeError("Faces have different sfnt versions")
    sfnt_version = sfnt_versions.pop()

    faces = [parse_ttf_tables(b) for b in face_binaries]
    tags = list(faces[0].keys())
    for face in faces[1:]:
        if list(face.keys()) != tags:
            raise RuntimeError("Faces have different table sets")

    per_face_tags = [tag for tag in tags
                     if any(face[tag][1] != faces[0][tag][1]
                            for face in faces[1:])]
    per_face_set = set(per_face_tags)
    shared_tags = [tag for tag in tags if tag not in per_face_set]

    num_tables = len(tags)
    log2 = int(math.log2(num_tables)) if num_tables > 0 else 0
    sr = (2 ** log2) * 16
    es = log2
    rs = num_tables * 16 - sr

    header_size = 12
    offsets_size = n * 4
    dir_size = 12 + num_tables * 16
    data_start = header_size + offsets_size + n * dir_size
    if data_start % 4:
        data_start += 4 - (data_start % 4)

    shared_offsets = {}
    per_face_offsets = {}
    table_data = b''
    current_offset = data_start

    def place(blob):
        nonlocal table_data, current_offset
        if current_offset % 4:
            pad = 4 - (current_offset % 4)
            table_data += b'\0' * pad
            current_offset += pad
        start = current_offset
        table_data += blob
        current_offset += len(blob)
        return start

    for tag in shared_tags:
        shared_offsets[tag] = place(faces[0][tag][1])
    for i, face in enumerate(faces):
        for tag in per_face_tags:
            per_face_offsets[(i, tag)] = place(face[tag][1])

    font_dirs = b''
    font_offsets = []
    for i, face in enumerate(faces):
        font_offsets.append(header_size + offsets_size + i * dir_size)
        fd = struct.pack('>4sHHHH', sfnt_version, num_tables, sr, es, rs)
        for tag in tags:
            if tag in per_face_set:
                cksum, data = face[tag]
                offset = per_face_offsets[(i, tag)]
            else:
                cksum, data = faces[0][tag]
                offset = shared_offsets[tag]
            fd += struct.pack('>4sIII', tag, cksum, offset, len(data))
        font_dirs += fd

    # Assemble TTC
    ttc = struct.pack('>4sHHI', b'ttcf', 1, 0, n)
    for fo in font_offsets:
        ttc += struct.pack('>I', fo)
    ttc += font_dirs
    if len(ttc) < data_start:
        ttc += b'\0' * (data_start - len(ttc))
    ttc += table_data

    tmp_path = output_path + '.tmp'
    with open(tmp_path, 'wb') as f:
        f.write(ttc)
    shutil.move(tmp_path, output_path)


# ── Main ───────────────────────────────────────────────────────────
def print_usage():
    print("Usage: python disguise_font.py [--otf-mode convert|rename|keep] "
          "<source> <target1> [target2 ...]")
    print("  Disguise the source font as each target font file.")
    print("  Supports TTF, OTF and TTC/OTC targets.")
    print("  --otf-mode convert  write real CFF/OTF into .otf targets (default)")
    print("  --otf-mode rename   keep TrueType data but write it as .ttf (drop .otf)")
    print("  --otf-mode keep     leave TrueType data inside the .otf file")


def parse_args(argv):
    otf_mode = 'convert'
    targets = []
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == '--otf-mode':
            if i + 1 >= len(argv):
                print("ERROR: --otf-mode needs a value")
                sys.exit(1)
            otf_mode = argv[i + 1]
            i += 2
            continue
        if arg.startswith('--otf-mode='):
            otf_mode = arg.split('=', 1)[1]
            i += 1
            continue
        if arg in ('-h', '--help'):
            print_usage()
            sys.exit(0)
        targets.append(arg)
        i += 1
    if otf_mode not in OTF_MODES:
        print(f"ERROR: invalid --otf-mode {otf_mode!r} "
              f"(choose from {', '.join(OTF_MODES)})")
        sys.exit(1)
    if len(targets) < 2:
        print_usage()
        sys.exit(1)
    return otf_mode, targets[0], targets[1:]


def main():
    otf_mode, source, targets = parse_args(sys.argv[1:])

    if not os.path.isfile(source):
        print(f"ERROR: Source font not found: {source}")
        sys.exit(1)

    probe = open_source(source)
    source_is_glyf = 'glyf' in probe
    probe.close()

    print(f"Source: {source}")
    print(f"OTF mode: {otf_mode}")
    total_fonts = 0

    for target_path in targets:
        if not os.path.isfile(target_path):
            print(f"WARNING: Target not found, skipping: {target_path}")
            continue

        label = os.path.basename(target_path)
        print(f"\nTarget: {label}")

        target_metas = extract_metadata(target_path)
        n = len(target_metas)
        print(f"  Found {n} font(s) in target")

        if n == 0:
            print("  WARNING: No metadata found, skipping")
            continue

        with open(target_path, 'rb') as f:
            is_ttc = f.read(4) == b'ttcf'

        ext = os.path.splitext(target_path)[1]
        ext_lower = ext.lower()

        if is_ttc or n > 1:
            build_ttc_with_shared_tables(source, target_metas, target_path)
            size_mb = os.path.getsize(target_path) / (1024 * 1024)
            print(f"  -> Saved collection: {label} ({n} fonts, {size_mb:.1f} MB)")
            if ext_lower in ('.otc', '.otf'):
                print("     note: collection keeps the source outline format")
        else:
            convert = otf_mode == 'convert' and ext_lower == '.otf' and source_is_glyf
            data = serialize_disguised(source, target_metas[0],
                                       convert_to_otf=convert)
            is_glyf = data[:4] == b'\x00\x01\x00\x00'
            out_path = target_path
            if otf_mode == 'rename' and is_glyf and ext_lower in RENAME_EXT:
                out_path = target_path[:-len(ext)] + RENAME_EXT[ext_lower]

            tmp_path = out_path + '.tmp'
            with open(tmp_path, 'wb') as f:
                f.write(data)
            shutil.move(tmp_path, out_path)
            if out_path != target_path and os.path.exists(target_path):
                os.remove(target_path)

            kind = 'OTF/CFF' if data[:4] == b'OTTO' else 'TTF (glyf)'
            print(f"  -> Saved {kind}: {os.path.basename(out_path)} "
                  f"({len(data) / 1024:.0f} KB)")
            if convert:
                print("     converted glyf outlines to CFF (TT hinting dropped)")

        for i, meta in enumerate(target_metas):
            ps = meta.get('PostScriptName', '?')
            fam = meta.get('Family', '?')
            sub = meta.get('SubFamily', '?')
            prefix = f"  [{i}]" if n > 1 else "  "
            print(f"{prefix} {fam} {sub} (PS: {ps})")
            total_fonts += 1

    print(f"\nDone! {total_fonts} font(s) disguised.")


if __name__ == '__main__':
    main()
