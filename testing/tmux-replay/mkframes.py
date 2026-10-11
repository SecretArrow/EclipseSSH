#!/usr/bin/env python3
"""Wrap raw bytes as a recording, so a hand-made reproduction runs through the same pipe as a real one.

  mkframes.py <raw> <out.frames> [--chunk N] [--escapes]

A real recording is a sequence of socket reads; this is the same thing with the boundaries chosen by
--chunk (default: one read, which is the case that never catches a split control string, so reproduce
with --chunk when the point is a boundary). With --escapes the input is text and \\x1b, \\e, \\r, \\n, \\t
and \\\\ are decoded, which keeps a fixture readable instead of a blob of control bytes.
"""
import argparse
import struct

SIMPLE = {"e": 0x1B, "E": 0x1B, "r": 0x0D, "n": 0x0A, "t": 0x09, "\\": 0x5C}


def unescape(text):
    out = bytearray()
    index = 0
    while index < len(text):
        char = text[index]
        if char == "\\":
            if index + 1 >= len(text):
                raise SystemExit("input ends with a lone backslash")
            marker = text[index + 1]
            if marker == "x":
                digits = text[index + 2 : index + 4]
                if len(digits) < 2:
                    raise SystemExit(f"\\x needs two hex digits at offset {index}")
                try:
                    out.append(int(digits, 16))
                except ValueError:
                    raise SystemExit(f"\\x{digits} is not hex at offset {index}")
                index += 4
                continue
            if marker not in SIMPLE:
                raise SystemExit(f"unknown escape \\{marker} at offset {index}")
            out.append(SIMPLE[marker])
            index += 2
            continue
        if ord(char) > 127:
            raise SystemExit(f"non-ASCII {char!r} at offset {index}; write it as \\xNN")
        out.append(ord(char))
        index += 1
    return bytes(out)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("raw")
    parser.add_argument("out")
    parser.add_argument("--chunk", type=int, default=0)
    parser.add_argument("--escapes", action="store_true", help="decode \\xNN escapes in the input")
    args = parser.parse_args()

    if args.escapes:
        with open(args.raw, encoding="ascii") as f:
            text = f.read()
        # The file's own trailing newline is the editor's, not part of the byte stream.
        if text.endswith("\n"):
            text = text[:-1]
        data = unescape(text)
    else:
        with open(args.raw, "rb") as f:
            data = f.read()
    chunks = [data] if args.chunk <= 0 else [data[i : i + args.chunk] for i in range(0, len(data), args.chunk)]

    with open(args.out, "wb") as out:
        for chunk in chunks:
            out.write(struct.pack(">I", len(chunk)))
            out.write(chunk)
    print(f"{args.out}: {len(chunks)} frames, {len(data)} bytes")


if __name__ == "__main__":
    main()
