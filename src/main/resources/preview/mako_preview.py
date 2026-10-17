import json
import os
import sys

try:
    from mako.lookup import TemplateLookup
except ImportError:
    sys.stderr.write("Mako preview requires the mako package in the selected Python SDK.\n")
    sys.exit(1)

raw = sys.stdin.read()
if not raw.strip():
    raw = "{}"
try:
    context = json.loads(raw)
except ValueError as exc:
    sys.stderr.write("Mako preview context is not a JSON object: %s\n" % exc)
    sys.exit(1)
if not isinstance(context, dict):
    sys.stderr.write("Mako preview context must be a JSON object.\n")
    sys.exit(1)

dirs = [part for part in os.environ.get("MAKO_PREVIEW_DIRS", "").split(os.pathsep) if part]
uri = os.environ.get("MAKO_PREVIEW_URI", "")
encoding = os.environ.get("MAKO_PREVIEW_ENCODING", "").strip() or None
lookup = TemplateLookup(directories=dirs, input_encoding="utf-8", output_encoding=encoding)
result = lookup.get_template(uri).render(**context)
if result is None:
    result = ""
if isinstance(result, bytes):
    sys.stdout.buffer.write(result)
else:
    sys.stdout.write(result)
