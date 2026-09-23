#!/usr/bin/env python3
"""Independent reader for StoryAtlas M1 evidence exports.

Usage:
  m1-workspace-export-reader.py --fixtures FIXTURES --story bell --export bell-export.json
  m1-workspace-export-reader.py --fixtures FIXTURES --evidence /private/tmp/workspace-m1-evidence/atlas/browser-N

The archive and its producer-generated selected-subset twins are the scientific oracle.  This
reader deliberately parses JSON, CSV and SVG itself; it does not call StoryAtlas export code.
"""
import argparse
import copy
import csv
import hashlib
import json
import re
import struct
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

VERSION = "storyatlas-evidence-export/v1"
SELECTION_NAMES = ("selection.json", "selection.csv", "selection.txt", "selection-receipt.json")


class InvalidExport(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise InvalidExport(message)


def sha256(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def archive_file(archive, entry):
    return next(item["utf8"] for item in archive["files"] if item["path"] == entry["path"])


def mapping_text(archive, policy):
    entry = next(
        entry for entry in archive["entries"]
        if entry["role"]["kind"] == "Mapping" and entry["role"].get("id") == policy
    )
    return archive_file(archive, entry)


def number(wire):
    require(isinstance(wire, str) and re.fullmatch(r"0x[0-9a-fA-F]{16}", wire),
            f"invalid producer IEEE-754 value {wire!r}")
    value = struct.unpack(">d", bytes.fromhex(wire[2:]))[0]
    rendered = repr(value)
    return rendered[:-2] if rendered.endswith(".0") else rendered


def outcome_cells(mapping):
    """Read producer MappingLink facts, rather than recomputing a score or normalization."""
    rows = mapping["outcomes"]
    require(isinstance(rows, list) and rows, "producer mapping has no outcomes")
    result = []
    for row in rows:
        links = row["mapping_links"]
        grouped = {}
        for link in links:
            destination = link["destination"]
            label = None
            kind = link.get("measure_kind")
            if kind == "RawScore":
                channel = link.get("channel", {}).get("value", "")
                raw = next(iter(row.get("measures", {}).get("raw", [])), {})
                label = f"Raw {channel} ({raw.get('scale', '')}; {raw.get('direction', '')})"
            elif kind == "NormalizedScoreMass":
                label = "Normalized score mass"
            elif kind == "TransportMass":
                label = "Transport mass"
            elif kind == "ModelPosterior":
                state = link.get("state", {}).get("value", {})
                state_type = state.get("type")
                if state_type == "Source":
                    fidelity = "source"
                elif state_type == "External":
                    fidelity = "external " + state.get("state", "")
                elif state_type == "Distorted":
                    fidelity = "distorted " + state.get("kind", "")
                else:
                    fidelity = state_type or "unknown"
                label = f"Model posterior: {fidelity}"
            if label is not None:
                grouped.setdefault(destination, []).append((label, number(link["raw_value"])))
        result.append(grouped)
    return result


def export_files(export):
    require(export.get("schemaVersion") == VERSION, "wrong export schemaVersion")
    files = export.get("files")
    require(isinstance(files, list), "export files is not an array")
    names = [item.get("name") for item in files]
    require(len(names) == len(set(names)), "duplicate export file name")
    result = {}
    for item in files:
        name, content = item.get("name"), item.get("content")
        require(isinstance(name, str) and isinstance(content, str), "invalid export file entry")
        require(item.get("byteLength") == len(content.encode("utf-8")), f"length mismatch: {name}")
        require(item.get("checksum") == sha256(content), f"checksum mismatch: {name}")
        result[name] = content
    required = {"matrix.svg", "matrix.txt", "investigation.json", *SELECTION_NAMES}
    require(required <= result.keys(), f"missing export files: {sorted(required - result.keys())}")
    return result


def validate_descriptor(archive, descriptor, policy):
    require(descriptor.get("schemaVersion") == "storyatlas-investigation/v2",
            "wrong investigation descriptor schemaVersion")
    require(descriptor.get("policy") == policy, "descriptor policy differs from requested producer policy")
    artifacts = descriptor.get("artifacts", {})
    require(artifacts.get("archiveSchema") == archive.get("schemaVersion"), "descriptor archive schema differs")
    actual = artifacts.get("members")
    require(isinstance(actual, list), "descriptor has no artifact members")
    expected = []
    for entry in archive["entries"]:
        disposition = entry.get("disposition", {})
        artifact = disposition.get("artifact")
        if disposition.get("status") == "Supplied" and artifact:
            expected.append((artifact["id"], artifact["checksum"], artifact["byteLength"]))
    observed = []
    for member in actual:
        disposition = member.get("disposition", {})
        if disposition.get("kind") == "supplied":
            observed.append((disposition.get("id"), disposition.get("checksum"), disposition.get("byteLength")))
    require(sorted(observed) == sorted(expected), "descriptor artifact identities differ from checked archive")


def validate_subset(files, fixture_dir, story):
    for name in SELECTION_NAMES:
        suffix = {"selection.json": "json", "selection.csv": "csv", "selection.txt": "txt",
                  "selection-receipt.json": "receipt.json"}[name]
        expected = (fixture_dir / f"{story}-authored-b-u0.{suffix}").read_text()
        require(files[name] == expected, f"{name} differs from producer selected-subset twin")
    # Parsing independently catches a row truncation that happens to retain a valid outer hash.
    rows = list(csv.DictReader(files["selection.csv"].splitlines()))
    require(rows, "selection.csv has no rows")
    selected = json.loads(files["selection.json"])["selection"]
    require(all(row["address"] in selected for row in rows), "CSV contains a non-selected recall address")


def svg_cells(svg):
    try:
        root = ET.fromstring(svg)
    except ET.ParseError as error:
        raise InvalidExport(f"matrix.svg is not XML: {error}") from error
    require(root.tag.endswith("svg"), "matrix.svg root is not SVG")
    cells = {}
    for group in root.iter():
        name = group.attrib.get("data-name")
        if name and re.fullmatch(r"matrix-r\d+-c\d+", name):
            require(name not in cells, f"duplicate SVG cell {name}")
            texts = [element.text or "" for element in group.iter()
                     if element.tag.endswith("text")]
            polygons = [element for element in group.iter() if element.tag.endswith("polygon")]
            require(polygons, f"SVG cell {name} has no border polygon")
            cells[name] = (texts, polygons[0].attrib)
    return root, cells


def chunks(label):
    return [label[index:index + 40] for index in range(0, len(label), 40)]


def external_columns(rows):
    # Producer ExternalState ordinal, declared in align/matrix.scala; only supplied states enter.
    ordinal = {"Association": 0, "Commentary": 1, "SourceConsistentInference": 2,
               "Intrusion": 3, "Uninterpretable": 4, "Unranked": 5}
    states = {destination.removeprefix("ext:") for row in rows for destination in row
              if destination.startswith("ext:")}
    require(states <= ordinal.keys(), f"unknown producer external state: {sorted(states - ordinal.keys())}")
    return ["ext:" + state for state in sorted(states, key=ordinal.__getitem__)]


def validate_matrix(files, archive, policy):
    mapping = json.loads(mapping_text(archive, policy))
    rows = outcome_cells(mapping)
    destinations = list(mapping["policies"]["target_universe_id"]["targets"]) + external_columns(rows)
    expected_names = {f"matrix-r{row}-c{column}" for row in range(len(rows))
                      for column in range(len(destinations))}
    _, svg = svg_cells(files["matrix.svg"])
    require(set(svg) == expected_names, "matrix.svg has truncated or extra fixed-cut cells")
    for row_index, row in enumerate(rows):
        for column_index, destination in enumerate(destinations):
            values = row.get(destination, [])
            require(destination in files["matrix.txt"], f"matrix.txt omits destination {destination}")
            require(destination in files["matrix.svg"], f"matrix.svg omits destination {destination}")
            expected_text = ([piece for label, value in values for piece in chunks(label) + [value]]
                             if values else ["Measure", "Not supplied"])
            actual_text, border = svg[f"matrix-r{row_index}-c{column_index}"]
            require(sorted(actual_text[:len(expected_text)]) == sorted(expected_text),
                    f"matrix.svg changes cell r{row_index} c{column_index}")
            for label, value in values:
                token = f"{label}: {value}"
                require(token in files["matrix.txt"], f"matrix.txt changes producer measure {token}")
            width = border.get("stroke-width")
            require(width == ("2" if row_index == 0 else "1"),
                    f"SVG cell r{row_index} c{column_index} has wrong selection outline width")


def validate(export_path, fixture_dir, story):
    archive = json.loads((fixture_dir / f"{story}.workspace.json").read_text())
    export = json.loads(export_path.read_text())
    files = export_files(export)
    descriptor = json.loads(files["investigation.json"])
    validate_descriptor(archive, descriptor, "authored-b")
    validate_subset(files, fixture_dir, story)
    validate_matrix(files, archive, "authored-b")


def resign(export):
    for item in export["files"]:
        item["byteLength"] = len(item["content"].encode("utf-8"))
        item["checksum"] = sha256(item["content"])
    return export


def mutate_svg(export, mutation):
    candidate = copy.deepcopy(export)
    item = next(x for x in candidate["files"] if x["name"] == "matrix.svg")
    root, cells = svg_cells(item["content"])
    mutation(root, cells)
    item["content"] = ET.tostring(root, encoding="unicode")
    return resign(candidate)


def self_test(export_path, fixture_dir, story):
    """In-memory controls: every corrupt export is re-signed before rejection."""
    original = json.loads(export_path.read_text())
    controls = []
    wrong_policy = copy.deepcopy(original)
    item = next(x for x in wrong_policy["files"] if x["name"] == "investigation.json")
    descriptor = json.loads(item["content"]); descriptor["policy"] = "historical-lexical"
    item["content"] = json.dumps(descriptor, separators=(",", ":")); controls.append(("wrong policy", resign(wrong_policy)))
    altered = copy.deepcopy(original)
    matrix = next(x for x in altered["files"] if x["name"] == "matrix.txt")
    match = re.search(r"Normalized score mass: ([0-9.eE+-]+)", matrix["content"])
    require(match, "control requires an authored normalized measure")
    matrix["content"] = re.sub(r"(Normalized score mass: )[0-9.eE+-]+", r"\g<1>0.999999", matrix["content"])
    controls.append(("re-signed altered normalized value", resign(altered)))
    truncated = copy.deepcopy(original)
    table = next(x for x in truncated["files"] if x["name"] == "selection.csv")
    table["content"] = "\n".join(table["content"].splitlines()[:-1]) + "\n"
    controls.append(("re-signed truncated selection rows", resign(truncated)))
    def altered_cell(root, cells):
        group, value = next(
            (group, element)
            for group in root.iter() if re.fullmatch(r"matrix-r\d+-c\d+", group.attrib.get("data-name", ""))
            for element in group.iter()
            if element.tag.endswith("text") and re.fullmatch(r"[0-9.eE+-]+", element.text or "")
        )
        value.text = "0.999999"
    controls.append(("re-signed altered SVG cell value", mutate_svg(original, altered_cell)))
    def swapped_values(root, cells):
        numeric = []
        for group in root.iter():
            name = group.attrib.get("data-name")
            if name and re.fullmatch(r"matrix-r\d+-c\d+", name):
                for element in group.iter():
                    if element.tag.endswith("text") and re.fullmatch(r"[0-9.eE+-]+", element.text or ""):
                        numeric.append((name, element))
        _, left, _, right = next((left_name, left, right_name, right)
                                 for index, (left_name, left) in enumerate(numeric)
                                 for right_name, right in numeric[index + 1:]
                                 if left_name != right_name and left.text != right.text)
        left.text, right.text = right.text, left.text
    controls.append(("re-signed swapped SVG cell values", mutate_svg(original, swapped_values)))
    for label, candidate in controls:
        try:
            # No artifact is changed: validation is performed from the in-memory document below.
            archive = json.loads((fixture_dir / f"{story}.workspace.json").read_text())
            data = export_files(candidate)
            descriptor = json.loads(data["investigation.json"])
            validate_descriptor(archive, descriptor, "authored-b")
            validate_subset(data, fixture_dir, story)
            validate_matrix(data, archive, "authored-b")
        except InvalidExport:
            continue
        raise AssertionError(f"corruption control accepted: {label}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", required=True, type=Path)
    parser.add_argument("--story", choices=("bell", "wog"))
    parser.add_argument("--export", type=Path)
    parser.add_argument("--evidence", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    paths = [args.export] if args.export else []
    if args.evidence:
        paths += sorted(args.evidence.rglob("*-export.json"))
    require(paths, "supply --export or --evidence containing *-export.json")
    for export_path in paths:
        story = args.story or next((s for s in ("bell", "wog") if export_path.name.startswith(s + "-")), None)
        require(story, f"cannot infer story from {export_path.name}; supply --story")
        validate(export_path, args.fixtures, story)
        if args.self_test:
            self_test(export_path, args.fixtures, story)
        print(json.dumps({"export": str(export_path), "story": story, "valid": True, "self_test": args.self_test}))


if __name__ == "__main__":
    try:
        main()
    except (InvalidExport, AssertionError, KeyError, StopIteration, json.JSONDecodeError) as error:
        print(f"invalid export: {error}", file=sys.stderr)
        sys.exit(1)
