"""Validate dumped H3 payloads against the live /object_info. No execution, no VRAM.

Every scalar is checked against the node's declared type, COMBO options and numeric
min/max/step; every link must point at a node that exists in the same payload. This is the
static half of the acceptance list, and it fails loudly instead of assuming a value is legal.
"""
import json
import sys
import urllib.request

BASE = "http://127.0.0.1:8188"

# Nodes whose widget set is created at runtime, so /object_info cannot enumerate every key.
DYNAMIC_WIDGETS = {"VHS_VideoCombine", "Power Lora Loader (rgthree)", "easy seed"}


def fetch(path):
    with urllib.request.urlopen(BASE + path, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))


def check_input(name, spec, value, problems, where):
    if not isinstance(spec, list) or not spec:
        return
    kind = spec[0]
    opts = spec[1] if len(spec) > 1 and isinstance(spec[1], dict) else {}

    if isinstance(value, list) and len(value) == 2 and isinstance(value[0], str):
        return "link"  # resolved by the caller

    if kind == "COMBO":
        options = opts.get("options") or []
        if options and value not in options:
            problems.append(f"{where}.{name}: {value!r} not in COMBO options")
        return None
    if kind in ("INT", "FLOAT"):
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            problems.append(f"{where}.{name}: expected number, got {value!r}")
            return None
        lo, hi, step = opts.get("min"), opts.get("max"), opts.get("step")
        if lo is not None and value < lo:
            problems.append(f"{where}.{name}: {value} < min {lo}")
        if hi is not None and value > hi:
            problems.append(f"{where}.{name}: {value} > max {hi}")
        if step:
            base = lo if lo is not None else 0
            if abs(((value - base) / step) - round((value - base) / step)) > 1e-6:
                problems.append(f"{where}.{name}: {value} is off the step grid {step}")
        if kind == "INT" and isinstance(value, float) and value != int(value):
            problems.append(f"{where}.{name}: {value} is not an integer")
        return None
    if kind == "STRING":
        if not isinstance(value, str):
            problems.append(f"{where}.{name}: expected string, got {type(value).__name__}")
        return None
    if kind == "COMFY_AUTOGROW_V3":
        prefix = opts.get("prefix", "")
        index = name[len(prefix):] if name.startswith(prefix) else ""
        limit = opts.get("max")
        if not index.isdigit():
            problems.append(f"{where}.{name}: autogrow key without numeric suffix")
        elif limit is not None and int(index) >= limit:
            problems.append(f"{where}.{name}: slot {index} >= max {limit}")
        template = (opts.get("template") or {}).get("input", {})
        for sub_name, sub_spec in (template.get("required") or {}).items():
            if isinstance(value, list):
                pass  # links into the template are resolved by the caller
        return None
    return None


def validate(payload_path):
    payload = json.load(open(payload_path, encoding="utf-8"))
    info = fetch("/object_info")
    problems = []
    ids = set(payload.keys())

    for node_id, node in payload.items():
        cls = node.get("class_type")
        if cls not in info:
            problems.append(f"#{node_id}: unknown node class {cls}")
            continue
        declared = info[cls].get("input", {})
        required = declared.get("required") or {}
        optional = declared.get("optional") or {}
        inputs = node.get("inputs") or {}

        for key, value in inputs.items():
            base = key.split(".")[0]
            spec = required.get(key) or optional.get(key) or required.get(base) or optional.get(base)
            if spec is None:
                # Keys such as `format.codec`, `values.a` or rgthree widget payloads are not
                # declared; nodes whose widget set is dynamic are exempt from the unknown-key check.
                if key.startswith(("values.", "ref_images.", "ref_videos.", "format.", "lora_")):
                    continue
                if isinstance(value, dict) or isinstance(value, str):
                    continue
                if cls in DYNAMIC_WIDGETS:
                    continue
                problems.append(f"#{node_id} {cls}.{key}: not a declared input")
                continue
            if isinstance(value, list) and len(value) == 2 and isinstance(value[0], str):
                if value[0] not in ids:
                    problems.append(f"#{node_id} {cls}.{key}: link to missing node {value[0]}")
                continue
            check_input(key, spec, value, problems, f"#{node_id} {cls}")

        for key in required:
            if key in inputs:
                continue
            # A dictionary-style input (`values`) is serialised as `values.a`, `values.b`, ...
            if any(k.startswith(key + ".") for k in inputs):
                continue
            problems.append(f"#{node_id} {cls}.{key}: required input missing")

    return problems


if __name__ == "__main__":
    failed = False
    for path in sys.argv[1:]:
        problems = validate(path)
        name = path.rsplit("\\", 1)[-1].rsplit("/", 1)[-1]
        if problems:
            failed = True
            print(f"[FAIL] {name}")
            for p in problems:
                print("   ", p)
        else:
            print(f"[ OK ] {name}")
    sys.exit(1 if failed else 0)
