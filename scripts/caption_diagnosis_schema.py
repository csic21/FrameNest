"""Bounded, fail-closed public fixture schema for the opt-in FN-76 test.

This validates evidence and ranges, never transcript or translation semantics.
No model inference, downloads, emulator access, or reference rewriting occurs.
"""
import hashlib
import re
from pathlib import Path
from urllib.parse import urlparse


HASH = re.compile(r"[0-9a-f]{64}\Z")
MODEL_HASHES = {
    "model.int8.onnx": (239233841, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
    "tokens.txt": (315894, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
}


def integer(value, minimum, maximum, name):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError(f"Invalid {name}")
    return value


def text(value, name, limit=4096):
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise ValueError(f"Invalid {name}")
    return value


def public_url(value):
    value = text(value, "public URL")
    parsed = urlparse(value)
    # Inputs are a deliberately narrow Wikimedia public-media benchmark.
    if parsed.scheme != "https" or parsed.hostname not in {"commons.wikimedia.org", "upload.wikimedia.org", "creativecommons.org"} or parsed.username or parsed.password:
        raise ValueError("Expected an approved public Wikimedia/Creative Commons HTTPS URL")
    return value


def relative_file(value):
    value = text(value, "input filename", 160)
    if Path(value).name != value or value in {".", ".."} or "\\" in value:
        raise ValueError("Input filename must be one safe basename")
    return value


def file_spec(spec, max_bytes):
    relative_file(spec["file"])
    integer(spec["bytes"], 1, max_bytes, "file length")
    if not isinstance(spec["sha256"], str) or not HASH.fullmatch(spec["sha256"]):
        raise ValueError("Invalid pinned SHA-256")


def validate_manifest(manifest):
    if manifest.get("schema_version") != 1 or type(manifest.get("schema_version")) is not int:
        raise ValueError("Unsupported diagnosis schema")
    text(manifest["id"], "fixture id", 100)
    duration = integer(manifest["duration_ms"], 1, 120000, "fixture duration")
    origin = integer(manifest["original_media_start_ms"], 0, 86400000, "original media offset")
    if manifest["source_language"] != "ja" or manifest["target_language"] != "zh":
        raise ValueError("This pinned diagnosis is Japanese to Chinese only")
    for key in ("source_url", "media_url", "license_url"):
        public_url(manifest[key])
    if manifest["license"] not in {"CC-BY-SA-4.0", "CC-BY-4.0"}:
        raise ValueError("Unreviewed public fixture license")
    text(manifest["author"], "attribution")
    file_spec(manifest["media"], 512 * 1024 * 1024)
    file_spec(manifest["wav"], 8 * 1024 * 1024)
    pcm = manifest["pcm"]
    file_spec(pcm, 3840000)
    if pcm["sample_rate_hz"] != 16000 or pcm["channels"] != 1 or pcm["encoding"] != "s16le":
        raise ValueError("Only signed 16-bit little-endian 16 kHz mono PCM is accepted")
    if pcm["samples"] != duration * 16 or pcm["bytes"] != pcm["samples"] * 2:
        raise ValueError("PCM duration/sample/byte count mismatch")
    reference = manifest["reference"]
    file_spec(reference, 2 * 1024 * 1024)
    public_url(reference["source_url"])
    public_url(reference["revision_url"])
    if reference["kind"] != "human_corrected_published_timed_text" or reference["verbatim_gold"] is not False:
        raise ValueError("Reference must retain its reviewed non-verbatim provenance")
    text(reference["provenance_note"], "reference provenance")
    units = manifest["units"]
    if not isinstance(units, list) or not 1 <= len(units) <= 64:
        raise ValueError("Expected 1 to 64 reviewed reference units")
    previous_end, ids, total_text = 0, set(), 0
    for unit in units:
        ident = text(unit["id"], "unit id", 100)
        if ident in ids:
            raise ValueError("Duplicate reference unit id")
        ids.add(ident)
        start = integer(unit["start_ms"], 0, duration - 1, "reference start")
        end = integer(unit["end_ms"], start + 1, duration, "reference end")
        if start < previous_end or end - start > 30000:
            raise ValueError("Reference units must be ordered, non-overlapping, and at most 30 seconds")
        previous_end = end
        if unit["original_start_ms"] != start + origin or unit["original_end_ms"] != end + origin:
            raise ValueError("Reference original-media offsets differ")
        if unit["unit_kind"] != "assistant_manually_grouped_published_caption_span":
            raise ValueError("Unknown reviewed reference unit kind")
        total_text += len(text(unit["text"], "reference text", 4096))
        cue_ids = unit["cue_ids"]
        if not isinstance(cue_ids, list) or not 1 <= len(cue_ids) <= 64 or any(type(v) is not int or v < 1 for v in cue_ids):
            raise ValueError("Reference unit must identify its published subtitle cues")
    if total_text > 16384:
        raise ValueError("Reference text exceeds the bounded diagnostic input")
    models = manifest["models"]
    if not isinstance(models, list) or len(models) != 2 or {m["file"] for m in models} != MODEL_HASHES.keys():
        raise ValueError("Use exactly the released SenseVoice model and token files")
    for model in models:
        file_spec(model, 240000000)
        if (model["bytes"], model["sha256"]) != MODEL_HASHES[model["file"]]:
            raise ValueError("Model differs from the released pinned pack")
    return manifest


def validate_reference(manifest, reference):
    if reference["reference_kind"] != manifest["reference"]["kind"] or reference["is_verbatim_gold"] is not False:
        raise ValueError("Reference provenance differs from the fixed manifest")
    if reference["source_offset_ms"] != manifest["original_media_start_ms"] or reference["duration_ms"] != manifest["duration_ms"]:
        raise ValueError("Reference media extent differs")
    if reference["source_url"] != manifest["reference"]["source_url"] or reference["revision_url"] != manifest["reference"]["revision_url"]:
        raise ValueError("Reference source/revision differs")
    if len(reference["units"]) != len(manifest["units"]):
        raise ValueError("Reference unit count differs")
    cues = {cue["id"]: cue for cue in reference["cues"]}
    if len(cues) != len(reference["cues"]):
        raise ValueError("Duplicate published subtitle cue id")
    for unit, original in zip(manifest["units"], reference["units"]):
        for key in ("id", "start_ms", "end_ms", "cue_ids", "text", "unit_kind"):
            if unit[key] != original[key]:
                raise ValueError(f"Reviewed reference unit differs: {key}")
        selected = [cues[i] for i in unit["cue_ids"]]
        if selected[0]["local_start_ms"] != unit["start_ms"] or selected[-1]["local_end_ms"] != unit["end_ms"]:
            raise ValueError("Reference unit must retain complete published cue boundaries")
        if "".join(cue["text"].replace("\n", "") for cue in selected) != unit["text"]:
            raise ValueError("Reference wording differs from its published cues")


def verify_file(root, spec):
    root = Path(root).resolve()
    path = root / relative_file(spec["file"])
    if not path.resolve().is_relative_to(root) or not path.is_file() or path.stat().st_size != spec["bytes"]:
        raise ValueError(f"Pinned file missing, escaped, or wrong size: {spec['file']}")
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != spec["sha256"]:
        raise ValueError(f"Pinned SHA-256 mismatch: {spec['file']}")
    return path
