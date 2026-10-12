#!/usr/bin/env python3
"""Record what tmux sends its client, from a real session, keeping the read boundaries.

The bytes are the whole point. A recording of what a program *wrote to its pane* (which is what
`tmux pipe-pane` gives) is not the same stream: tmux is an emulator in between, and it sends its client
only the cells it believes changed, plus its own framing - passthrough DCS included. The app is the
client, so the client-bound bytes are the ones to replay.

Frames are 4-byte big-endian length prefix + payload, so the parser sees the same boundaries the socket
did.

  record.py OUT.frames SECONDS [--cols N] [--rows N] [--term T] [--config PATH] [--send S:TEXT]... -- COMMAND

`--send` types into the client's terminal at a given second, as many times as given, so a program that
will not draw anything worth recording until it has been answered - a first-run trust dialog, a prompt -
can be recorded too. Without it the recording stops at whatever the program shows before it asks
something.

Known limit, and a deliberate one: without `--answer` this harness answers none of tmux's questions (DA1,
XTVERSION, DECRQM), so tmux falls back to its own defaults for the capabilities a real client would have
reported. That changes which sequences tmux *chooses*; it does not change how they are framed, which is
what is being captured.

`--answer` closes that gap for the capabilities the app actually reports, and it matters for exactly one
thing: tmux asks `DECRQM 2026` and wraps its repaints in `ESC [ ? 2026 h`/`l` only once it is told the
mode is recognized. A recording made without `--answer` therefore cannot contain a synchronized-output
batch, and replaying it proves nothing about what the app does with one.

Diagnosis only. Nothing here configures the far side for a fix; see the note in README.md.
"""
import argparse
import fcntl
import os
import pty
import re
import select
import signal
import struct
import subprocess
import sys
import termios
import time

SOCKET = "eclipse-replay"

# What the app answers when tmux asks its terminal a question. Mirrors `AnsiTerminalBuffer`'s reply
# table - the DECRQM mode list, its three answers, and the DA1/DA2/XTVERSION strings - so a recording
# made with `--answer` contains the sequences tmux only chooses once it is told. The states are the ones
# a fresh session reports, which is when tmux asks; a session that had already set a mode would report
# differently and this table does not track it.
DECRQM_MODES = frozenset(
    # The modes the buffer keeps a state for, plus the mouse modes it lists by name.
    [1, 4, 6, 7, 12, 25, 47, 1000, 1002, 1003, 1005, 1006, 1015, 1047, 1049, 2004, 2026]
)
# On in a fresh session: auto-wrap, cursor blink and cursor visibility. Everything else the buffer
# knows about starts off. 1048 is deliberately absent - it saves a cursor rather than switching
# anything, so the app answers unrecognized for it.
DECRQM_ON = frozenset([7, 12, 25])
FIXED_REPLIES = (
    (b"\x1b[c", b"\x1b[?1;2c"),
    (b"\x1b[>c", b"\x1b[>0;95;0c"),
    (b"\x1b[>0q", b"\x1bP>|EclipseSSH(1.22.0)\x1b\\"),
)
DECRQM = re.compile(rb"\x1b\[(\?)?(\d+)\$p")


def answers_for(chunk: bytes) -> bytes:
    """The replies the app would send for the questions in one chunk, concatenated.

    Written back to the client's terminal after the chunk is recorded, so the recording holds the query
    as well - the app sees it too, and a replay that lacked it would not be the same session.
    """
    replies = bytearray()
    for question, answer in FIXED_REPLIES:
        replies += answer * chunk.count(question)
    for match in DECRQM.finditer(chunk):
        private, mode = match.group(1), int(match.group(2))
        if mode not in DECRQM_MODES:
            state = 0
        elif mode in DECRQM_ON:
            state = 1
        else:
            state = 2
        replies += b"\x1b[" + (private or b"") + str(mode).encode() + b";" + str(state).encode() + b"$y"
    return bytes(replies)


def win_size(cols: int, rows: int) -> bytes:
    return struct.pack("HHHH", rows, cols, 0, 0)


def decode(text: str) -> bytes:
    """`\\r` and `\\x1b[B` as the bytes they name, so a keypress fits on a command line."""
    try:
        return text.encode("ascii").decode("unicode_escape").encode("latin-1")
    except UnicodeEncodeError:
        raise SystemExit(f"record.py: --send TEXT must be ASCII, got {text!r}")


def parse_send(item: str) -> tuple[float, bytes]:
    at, separator, text = item.partition(":")
    if not separator:
        raise SystemExit(f"record.py: --send wants SECONDS:TEXT, got {item!r}")
    return float(at), decode(text)


def main() -> int:
    # The command comes after a bare `--`, split by hand: argparse's REMAINDER starts swallowing at the
    # first positional, so options written before the command would silently become part of it.
    argv = sys.argv[1:]
    if "--" not in argv:
        print("usage: record.py OUT.frames SECONDS [options] -- COMMAND", file=sys.stderr)
        return 2
    split = argv.index("--")
    command = argv[split + 1 :]
    if not command:
        print("record.py: nothing to run after --", file=sys.stderr)
        return 2

    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("seconds", type=float)
    parser.add_argument("--cols", type=int, default=52)
    parser.add_argument("--rows", type=int, default=60)
    parser.add_argument("--term", default="xterm-256color")
    parser.add_argument("--config", default=os.path.expanduser("~/.tmux.conf"))
    parser.add_argument(
        "--send",
        action="append",
        default=[],
        metavar="SECONDS:TEXT",
        help="write TEXT to the client's terminal after SECONDS (\\r, \\x1b[B and friends decoded)",
    )
    parser.add_argument(
        "--answer",
        action="store_true",
        help="answer tmux's terminal queries the way the app does, so tmux chooses its supported paths",
    )
    args = parser.parse_args(argv[:split])

    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, win_size(args.cols, args.rows))
    # The pty is the tmux client's terminal, so the size here *is* the client size: 52x60 as recorded.
    env = dict(os.environ, TERM=args.term)
    tmux = ["tmux", "-L", SOCKET]
    if os.path.exists(args.config):
        tmux += ["-f", args.config]

    def child_setup() -> None:
        os.setsid()
        fcntl.ioctl(0, termios.TIOCSCTTY, 0)

    client = subprocess.Popen(
        tmux + ["new-session", "-A", "-s", "capture", "-x", str(args.cols), "-y", str(args.rows), *command],
        stdin=slave, stdout=slave, stderr=slave, env=env, preexec_fn=child_setup, close_fds=True,
    )
    os.close(slave)

    frames = 0
    total = 0
    started = time.monotonic()
    deadline = started + args.seconds
    schedule = sorted(parse_send(item) for item in args.send)
    with open(args.out, "wb") as out:
        while time.monotonic() < deadline:
            elapsed = time.monotonic() - started
            while schedule and schedule[0][0] <= elapsed:
                os.write(master, schedule.pop(0)[1])
            # A short wait while a keypress is still due, so the schedule is not rounded to whatever the
            # session happened to print; the deadline once nothing is left to send. Never zero: a wait of
            # zero returns "nothing to read" immediately, and reading that as the end of the recording
            # would stop it on the very keypress it was scheduled to send. The loop's own condition is
            # what ends it, so a timeout here just goes round again.
            wait = deadline - time.monotonic()
            if schedule:
                wait = min(wait, schedule[0][0] - elapsed)
            if wait <= 0:
                continue
            ready, _, _ = select.select([master], [], [], wait)
            if not ready:
                continue
            try:
                chunk = os.read(master, 65536)
            except OSError:
                break
            if not chunk:
                break
            out.write(struct.pack(">I", len(chunk)))
            out.write(chunk)
            frames += 1
            total += len(chunk)
            if args.answer:
                reply = answers_for(chunk)
                if reply:
                    os.write(master, reply)

    client.send_signal(signal.SIGTERM)
    subprocess.run(tmux + ["kill-server"], capture_output=True)
    try:
        client.wait(timeout=5)
    except subprocess.TimeoutExpired:
        client.kill()
    print(f"{args.out}: {frames} frames, {total} bytes, {args.cols}x{args.rows}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
