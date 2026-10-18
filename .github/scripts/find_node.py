#!/usr/bin/env python3
"""Prints the centre "x y" of the first on-screen UI node whose text or content-desc matches.

Usage: find_node.py <uiautomator dump.xml> <label>
An exact match wins; otherwise the first node whose text or content-desc contains the label.
Exits with status 1 when nothing matches.
"""
import re
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    path, label = sys.argv[1], sys.argv[2]
    nodes = []
    for node in ET.parse(path).getroot().iter("node"):
        nums = [int(n) for n in re.findall(r"-?\d+", node.get("bounds", ""))]
        if len(nums) != 4:
            continue
        x1, y1, x2, y2 = nums
        if x2 - x1 < 4 or y2 - y1 < 4:
            continue  # off screen or clipped away
        nodes.append((node, (x1 + x2) // 2, (y1 + y2) // 2))

    for exact in (True, False):
        for node, x, y in nodes:
            for attr in ("text", "content-desc"):
                value = node.get(attr) or ""
                if (value == label) if exact else (label in value):
                    print(x, y)
                    return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
