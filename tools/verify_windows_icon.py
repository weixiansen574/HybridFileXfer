#!/usr/bin/env python3
"""Verify jpackage's final PE resources against the exact supplied ICO frames."""
import argparse
import hashlib
from pathlib import Path
import struct


def _read(data, fmt, offset):
    size = struct.calcsize(fmt)
    if offset < 0 or offset + size > len(data):
        raise ValueError("Truncated executable/icon structure")
    return struct.unpack_from(fmt, data, offset)


def _slice(data, offset, size):
    if offset < 0 or size < 0 or offset + size > len(data):
        raise ValueError("Resource is outside its containing file")
    return data[offset:offset + size]


def ico_frames(path):
    data = Path(path).read_bytes()
    reserved, kind, count = _read(data, "<HHH", 0)
    if reserved or kind != 1 or not 1 <= count <= 64:
        raise ValueError("Invalid ICO header")
    frames = []
    for index in range(count):
        entry = _read(data, "<BBBBHHII", 6 + index * 16)
        frames.append((entry[:7], _slice(data, entry[7], entry[6])))
    return frames


def pe_resources(path):
    data = Path(path).read_bytes()
    if data[:2] != b"MZ":
        raise ValueError("Not a Windows executable")
    header, = _read(data, "<I", 0x3c)
    if _slice(data, header, 4) != b"PE\0\0":
        raise ValueError("Missing PE signature")
    sections, = _read(data, "<H", header + 6)
    optional_size, = _read(data, "<H", header + 20)
    optional = header + 24
    magic, = _read(data, "<H", optional)
    if magic not in (0x10b, 0x20b):
        raise ValueError("Unsupported PE optional header")
    directories = optional + (112 if magic == 0x20b else 96)
    resource_rva, resource_size = _read(data, "<II", directories + 16)
    if not resource_rva or not resource_size:
        raise ValueError("Executable has no resources")
    section_table = []
    for index in range(sections):
        _, virtual_size, address, raw_size, raw_offset = _read(
            data, "<8sIIII", optional + optional_size + index * 40)
        section_table.append((address, virtual_size, raw_size, raw_offset))

    def offset(rva, size=1):
        for address, virtual_size, raw_size, raw_offset in section_table:
            if address <= rva < address + max(virtual_size, raw_size):
                relative = rva - address
                if relative + size > raw_size:
                    raise ValueError("Resource extends outside its PE section")
                return raw_offset + relative
        raise ValueError("Resource RVA does not map to a PE section")

    base = offset(resource_rva, resource_size)
    resource_data = _slice(data, base, resource_size)
    resources = {}

    def visit(relative, path=()):
        if len(path) > 3:
            raise ValueError("Unexpected PE resource nesting")
        named, ids = _read(resource_data, "<HH", relative + 12)
        for index in range(named + ids):
            name, target = _read(resource_data, "<II", relative + 16 + index * 8)
            if name & 0x80000000:
                name_offset = name & 0x7fffffff
                length, = _read(resource_data, "<H", name_offset)
                name = _slice(resource_data, name_offset + 2, length * 2).decode("utf-16le")
            key = path + (name,)
            if target & 0x80000000:
                visit(target & 0x7fffffff, key)
            else:
                rva, size, _, _ = _read(resource_data, "<IIII", target)
                if key in resources:
                    raise ValueError("Duplicate PE resource")
                resources[key] = _slice(data, offset(rva, size), size)
    visit(0)
    return resources


def verify_windows_icon(executable, icon):
    expected = ico_frames(icon)
    sizes = [(entry[0] or 256, entry[1] or 256) for entry, _ in expected]
    if sizes != [(16, 16), (32, 32), (48, 48), (256, 256)]:
        raise ValueError("Windows package icon must contain 16/32/48/256 pixel frames")
    resources = pe_resources(executable)
    groups = [(key, data) for key, data in resources.items() if key[0] == 14]
    if len(groups) != 1:
        raise ValueError("Packaged launcher must have exactly one application icon group")
    key, group = groups[0]
    if len(key) != 3 or _read(group, "<HHH", 0) != (0, 1, len(expected)):
        raise ValueError("Packaged icon group does not match the supplied ICO")
    referenced = set()
    report = []
    for index, (descriptor, payload) in enumerate(expected):
        actual = _read(group, "<BBBBHHIH", 6 + index * 14)
        resource_key = (3, actual[7], key[2])
        if actual[:7] != descriptor or resources.get(resource_key) != payload:
            raise ValueError("Packaged icon frame differs from supplied ICO: " + str(sizes[index]))
        referenced.add(resource_key)
        report.append({"size": sizes[index][0], "bytes": len(payload),
                       "sha256": hashlib.sha256(payload).hexdigest()})
    if referenced != {key for key in resources if key[0] == 3}:
        raise ValueError("Packaged launcher contains unverified extra icon resources")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("executable", type=Path)
    parser.add_argument("icon", type=Path)
    args = parser.parse_args()
    for frame in verify_windows_icon(args.executable, args.icon):
        print("{size}px: {bytes} bytes SHA256={sha256}".format(**frame))
