#!/usr/bin/env python3
"""Record what tmux sends its client, from a real session, keeping the read boundaries.

The bytes are the whole point. A recording of what a program *wrote to its pane* (which is what
`tmux pipe-pane` gives) is not the same stream: tmux is an emulator in between, and it sends its client
only the cells it believes changed, plus its own framing - passthrough DCS included. The app is the
client, so the client-bound bytes are the ones to replay.

Frames are 4-byte big-endian length prefix + payload, so the parser sees the same boundaries the socket
did.

  record.py OUT.frames SECONDS [--cols N] [--rows N] [--term T] [--config PATH] -- COMMAND

Known limit, and a deliberate one: tmux asks its terminal questions (DA1, XTVERSION, DECRQM) and this
harness answers none of them, so tmux falls back to its own defaults for the capabilities a real client
would have reported. That changes which sequences tmux *chooses*; it does not change how they are
framed, which is what is being captured.

Diagnosis only. Nothing here configures the far side for a fix; see the note in README.md.
"""
import argparse
import fcntl
import os
import pty
import select
import signal
import struct
import subprocess
import sys
import termios
import time

SOCKET = "eclipse-replay"


def win_size(cols: int, rows: int) -> bytes:
    return struct.pack("HHHH", rows, cols, 0, 0)


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
    deadline = time.monotonic() + args.seconds
    with open(args.out, "wb") as out:
        while time.monotonic() < deadline:
            ready, _, _ = select.select([master], [], [], max(0.0, deadline - time.monotonic()))
            if not ready:
                break
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
