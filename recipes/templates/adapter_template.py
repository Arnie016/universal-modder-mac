#!/usr/bin/env python3
"""A complete umx-adapter/0.1 adapter for a demo counter. Copy it to connect a new game to the lab.

This is a template, not a game integration: the "game" below is one number in memory. It exists so the protocol
and the envelope rules can be read, run and tested without any game installed:

    um lab accept recipes/templates/demo-counter --allow-recipe-cmd

The lab starts the adapter and talks to it over stdin/stdout, one JSON object per line each way:

    -> {"id": 1, "op": "hello"}
    <- {"id": 1, "ok": true, "protocol": "umx-adapter/0.1", "adapter": "template", "game": {...}, "turn_ms": 100}
    -> {"id": 2, "op": "start", "mod": null, "scenario": null, "player": 1, "commands": ["demo-add"],
        "evidence_dir": "..."}                      a folder the adapter may write its logs to
    <- {"id": 2, "ok": true, "session": 81723, "epoch": 0, "timeMs": 0}
    -> {"id": 3, "op": "send", "commands": [{"type": "demo-add", "amount": 2, "src": "lab", "seq": 1, ...}]}
    <- {"id": 3, "ok": true, "queued": 1}
    -> {"id": 4, "op": "step", "turns": 5}         the queued commands apply in the first of these turns
    <- {"id": 4, "ok": true, "timeMs": 500, "acks": [{"n": 1, "type": "demo-add", "ok": true, "code": "added", ...}]}
    -> {"id": 5, "op": "observe", "player": 1}
    <- {"id": 5, "ok": true, "state": {"session": 81723, "epoch": 0, "counter": 2, "lastSeq": {"lab": 1}, ...}}
    -> {"id": 6, "op": "stop"}
    <- {"id": 6, "ok": true, "stopped": true}

A failed request answers {"id": n, "ok": false, "error": "...", "code": "..."}. Stdout carries only these lines;
log to stderr.

To adapt it to a real game, keep `serve` and the envelope rules in `World.admit` (or move them into the game's own
scripting, as the 0 A.D. recipe does in UmCrossover.js), and replace the World methods marked GAME with calls into
the game: start it, forward commands, advance it by turns, read its state. Keep the game paused between steps: the
lab owns the clock.
"""
from __future__ import annotations

import json
import random
import sys

PROTOCOL = "umx-adapter/0.1"
TURN_MS = 100
ACKS_KEPT = 32
SOURCES_KEPT = 32


class World:
    """GAME: everything here stands in for the game. One counter that `demo-add` commands change."""

    def __init__(self, player: int):
        self.player = player
        self.session = random.SystemRandom().randint(1, 2**31 - 2)   # new every start, so old commands go stale
        self.epoch = 0             # a game that can load saves adds 1 on every load
        self.time_ms = 0
        self.counter = 0
        self.last_seq: dict[str, int] = {}
        self.acks: list[dict] = []
        self.ack_count = 0

    # The envelope rules every adapter keeps, in this order. --------------------------------------------
    def admit(self, cmd: dict) -> dict:
        if cmd.get("session") != self.session:
            return dict(ok=False, code="stale-session", text="That command was for another game session")
        if cmd.get("epoch") != self.epoch:
            return dict(ok=False, code="stale-epoch", text="That command was sent before the last load")
        src, seq = cmd.get("src"), cmd.get("seq")
        if not isinstance(src, str) or not src or not isinstance(seq, int) or isinstance(seq, bool) or seq < 1:
            return dict(ok=False, code="bad-envelope", text="A command needs src and a positive whole seq")
        last = self.last_seq.get(src, 0)
        if seq <= last:
            return dict(ok=False, code="duplicate", text="")
        self.last_seq.pop(src, None)
        self.last_seq[src] = seq
        if len(self.last_seq) > SOURCES_KEPT:
            self.last_seq.pop(next(iter(self.last_seq)))
        return dict(ok=True, gap=last > 0 and seq > last + 1)

    def handle(self, cmd: dict) -> dict:
        verdict = self.admit(cmd)
        result = self.apply(cmd) if verdict["ok"] else verdict
        self.ack_count += 1
        ack = dict(n=self.ack_count, timeMs=self.time_ms, player=self.player, type=cmd.get("type"),
                   src=cmd.get("src"), seq=cmd.get("seq"), **{k: v for k, v in result.items() if k != "gap"})
        if verdict.get("gap"):
            ack["gap"] = True
        self.acks = (self.acks + [ack])[-ACKS_KEPT:]
        return ack

    # GAME: what the commands do. ------------------------------------------------------------------------
    def apply(self, cmd: dict) -> dict:
        if cmd.get("type") == "demo-add":
            amount = cmd.get("amount")
            if not isinstance(amount, int) or isinstance(amount, bool) or not 1 <= amount <= 10:
                return dict(ok=False, code="bad-args", text="amount must be a whole number from 1 to 10")
            self.counter += amount
            return dict(ok=True, code="added", text=f"Counter is now {self.counter}", counter=self.counter)
        return dict(ok=False, code="unknown-type", text=f"Unknown command {cmd.get('type')}")

    def advance(self, turns: int):
        self.time_ms += turns * TURN_MS

    def observe(self) -> dict:
        return dict(v=1, session=self.session, epoch=self.epoch, timeMs=self.time_ms, player=self.player,
                    counter=self.counter, lastSeq=dict(self.last_seq), acks=self.acks[-8:], ackCount=self.ack_count)


class Adapter:
    def __init__(self):
        self.world: World | None = None
        self.queue: list[dict] = []
        self.allowed: set[str] = set()
        self.read = 0

    def need(self) -> World:
        if not self.world:
            raise AdapterError("send start first", "not-started")
        return self.world

    def op_hello(self, **_):
        return dict(protocol=PROTOCOL, adapter="template",
                    game=dict(name="demo counter (adapter template, not a game)", version="0.1.0"),
                    turn_ms=TURN_MS, profile="reference")

    def op_start(self, mod=None, scenario=None, player=1, commands=(), **_):   # ignore params it does not use
        self.world, self.queue, self.allowed, self.read = World(int(player)), [], set(commands), 0
        return dict(session=self.world.session, epoch=self.world.epoch, timeMs=self.world.time_ms)

    def op_send(self, commands=(), **_):
        self.need()
        for c in commands:
            if not isinstance(c, dict) or c.get("type") not in self.allowed:
                raise AdapterError(f"{c.get('type') if isinstance(c, dict) else c!r} is not one of this recipe's "
                                   "commands", "not-allowed")
        self.queue += list(commands)
        return dict(queued=len(self.queue))

    def op_step(self, turns=1, **_):
        world = self.need()
        turns = int(turns)
        if turns < 1:
            raise AdapterError("turns must be at least 1", "bad-args")
        for cmd in self.queue:              # queued commands apply in the first turn
            world.handle(cmd)
        self.queue = []
        world.advance(turns)
        acks = [a for a in world.acks if a["n"] > self.read]
        dropped = bool(acks) and acks[0]["n"] > self.read + 1
        self.read = world.ack_count
        return dict(timeMs=world.time_ms, acks=acks, **({"acks_dropped": True} if dropped else {}))

    def op_observe(self, player=None, **_):
        return dict(state=self.need().observe())

    def op_stop(self, **_):
        stopped, self.world = self.world is not None, None
        return dict(stopped=stopped)


class AdapterError(Exception):
    def __init__(self, msg: str, code: str = "error"):
        super().__init__(msg)
        self.code = code


def serve(impl, stdin=sys.stdin, stdout=sys.stdout):
    for line in stdin:
        if not line.strip():
            continue
        rid = None
        try:
            req = json.loads(line)
            rid = req.get("id")
            fn = getattr(impl, "op_" + str(req.get("op")), None)
            if not fn:
                raise AdapterError(f"unknown op {req.get('op')!r}", "unknown-op")
            resp = dict(ok=True, **fn(**{k: v for k, v in req.items() if k not in ("id", "op")}))
        except AdapterError as e:
            resp = dict(ok=False, error=str(e), code=e.code)
        except Exception as e:      # report and keep serving
            resp = dict(ok=False, error=f"{type(e).__name__}: {e}", code="error")
        stdout.write(json.dumps(dict(id=rid, **resp)) + "\n")
        stdout.flush()


if __name__ == "__main__":
    serve(Adapter())
