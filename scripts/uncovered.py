#!/usr/bin/env python3
"""Lines of iron-mcp-core no test reached, from the last scripts/coverage.sh run: file:line  what (branch?)."""
import glob, sys, xml.etree.ElementTree as ET
xml = "target/coverage-report/scoverage.xml"
only = sys.argv[1] if len(sys.argv) > 1 else ""
seen = set()
for s in ET.parse(xml).getroot().iter("statement"):
    if int(s.get("invocation-count", "0")) > 0 or s.get("ignored") == "true":
        continue
    src = s.get("source", "").split("/ironmcp/", 1)[-1]
    if only not in src:
        continue
    key = (src, int(s.get("line")))
    if key in seen:
        continue
    seen.add(key)
    print(f"{src}:{key[1]:<4} {'B ' if s.get('branch') == 'true' else '  '}{s.get('method')}")
