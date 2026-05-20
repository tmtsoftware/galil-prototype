# Galil I/O

Low-level protocol layer for talking to a Galil DMC-400x0 or DMC-500x0 motion
controller. This module hides the wire protocol — byte framing, response
parsing, the 80-character line limit, the DL/UL program transfer semantics,
the read-timeout management required by slow controller operations — behind
a small, transport-agnostic API.

The HCD is verified against both the DMC-400x0 and DMC-500x0 series; the two
families share the host-side command and response interface for the
operations this module performs. (The 500-series adds EtherCAT support that
the HCD does not use.)

## What's in here

- **`GalilIo` (abstract base)** — command framing, response parsing, the
  80-char line guard, DL/UL program upload/download, read-timeout save and
  restore. Transport-agnostic.
- **`GalilIoTcp`** — TCP transport. Implements `setReadTimeout` /
  `getReadTimeout` via the underlying socket's `SO_TIMEOUT`, and sets
  `SO_KEEPALIVE` on the socket so dead peers surface promptly instead of
  hanging silently.
- **`GalilIoUdp`** — UDP transport.
- **`DataRecord`** — codec for the binary `QR` data record. Bidirectional:
  parses records returned by a controller, and writes records back to bytes
  (the Galil simulator uses the encoding side to fabricate `QR` responses
  without hand-rolling the wire layout).

## Send semantics

The wire protocol is line-oriented ASCII commands terminated by `\r\n`. A
command receives one of three response shapes:

- `:` — acknowledged, no return data (e.g. a variable assignment).
- `<data>\r\n:` — acknowledged with data (e.g. `MG _TPA`).
- `?` — command rejected.

`send(cmd)` writes one line and reads the response, returning a list of
`(subCommand, ByteString)` pairs. A single command returns a one-element
list; a compound command (semicolon-separated, e.g. `a=1;b=2;MG a`) returns
one element per sub-command.

```scala
io.send("MG _TPA")
// → List(("MG _TPA", ByteString("12345")))

io.send("speed[0]=500;MG speed[0]")
// → List(("speed[0]=500", ByteString("")),
//        ("MG speed[0]",   ByteString("\r\n500.0000")))
```

An empty `ByteString` represents a `:` acknowledgment. A `?` response is
preserved verbatim in the `ByteString` so callers can detect rejection. For
compound responses the splitter currently normalises `?` to `:` before
splitting, so a mid-batch rejection is not distinguishable at this layer; if
the rejection signal matters, use `sendAndWaitForPrompt(cmd)` on the single
command — it throws `RuntimeException` on `?`.

```scala
io.sendAndWaitForPrompt("HX")          // returns on `:`, throws on `?`
```

## The 80-character line guard

The Galil parser has a hard 80-character per-line buffer; lines over the
limit are silently truncated or rejected without any synchronous error
signal back to the host. Two defenses live here so this can't pass
unnoticed:

- **`send` throws `IllegalArgumentException`** before any socket write if
  `cmd.length > 80`. The exception names the offending command and its
  length.
- **`GalilIo.chunkCompound(subCommands: Seq[String]): Seq[String]`** —
  greedy packer that returns ≤80-char compound chunks ready for individual
  `send()` calls. Use it wherever a dynamic compound's total length isn't
  statically bounded.

```scala
GalilIo.chunkCompound(Seq("speed[0]=20000", "accel[0]=256000",
                          "decel[0]=256000", "cpr[0]=629760"))
// → Seq("speed[0]=20000;accel[0]=256000",
//       "decel[0]=256000;cpr[0]=629760")
```

The limit is exposed as `GalilIo.maxCommandLineLength` for callers that
need it.

## Program upload (DL) and download (UL)

`uploadProgram` and `downloadProgram` encapsulate the program-transfer
sequences. DL is unusual in two ways:

1. **The `DL` command does not acknowledge synchronously.** Once `DL` is
   sent, the controller is silent through the entire program stream. After
   the host sends the `\` terminator, the controller emits *two* `:` acks:
   one for `\` (exit DL mode), then a deferred ack for the original `DL`
   itself. Both must be consumed before the next command, or the next
   command's response stream will be desynchronised by one ack.

2. **Per-line rejection is signalled asynchronously.** If the DL parser
   rejects a line (e.g. exceeds the 80-char limit, syntax error), it emits
   `?` characters into the receive stream *after* program streaming
   completes but *before* `\` is sent. There is no synchronous error at
   the rejected-line moment — the host has to sample the receive buffer
   between the program stream and the terminator.

`uploadProgram(program: String): Unit` implements both:

- Drains the receive buffer before `DL` (any stray byte would be misread
  as part of the DL response).
- Streams `DL` and the program text.
- Drains again with a short timeout; if any `?` is present, throws
  `RuntimeException` with the count of rejected lines and a preview.
- Sends `\` via `sendAndWaitForPrompt` (throws on `?`).
- Drains the deferred `DL` ack so the next command isn't confused.
- Extends the socket read timeout to 10 s for the duration (large
  programs race the default 3 s timeout on the post-`\` ack), restoring the
  caller's original value in a `finally` — including on the exception
  paths.

`uploadProgram` is a pure protocol primitive. Halting any running threads
(`HX`) before uploading is the caller's responsibility.

```scala
try {
  io.uploadProgram(programText)
} catch {
  case e: RuntimeException => /* one or more lines were rejected */
}
```

`downloadProgram(): String` sends `UL`, reads the program text, strips the
trailing `\` (or `^Z`) terminator and surrounding whitespace, returns the
text.

## Read timeout management

`setReadTimeout(ms)` and `getReadTimeout: Int` on the abstract base let
callers extend the socket read timeout for known-slow operations and restore
it afterward. `setReadTimeout(0)` blocks indefinitely.

`uploadProgram` manages its own timeout internally; other slow operations
managed by callers include `BP` (burn program to EEPROM — multi-second flash
write) and `MG _NO` polling through brushless commutation (`BZ` pauses all
controller-side communication for several seconds per axis).

The TCP transport delegates to `socket.setSoTimeout` / `socket.getSoTimeout`.
The default abstract implementation is a no-op (returns 0) and is intended
for transports that don't need timeout management.

## DataRecord

`DataRecord` is the codec for the binary `QR` response. A record contains:

- **Header** — 4 bytes describing the data-record layout: which axis blocks
  are present (a bitmask determined by the controller's configured axes)
  and the total record size. Variable-format because controllers with fewer
  axes emit shorter records.
- **General state** (52 bytes) — sample number, motor on/off bitmask, thread
  status bitmask, axis bit assignments, amplifier status.
- **Per-axis status** (one block per present axis) — status bits (motor on,
  moving, in-motion, limit hit, position error, on-target, etc.), motor
  position, position error, auxiliary encoder position, velocity, torque,
  analog input.

The codec is bidirectional. `DataRecord(byteString)` parses a record off
the wire; `dataRecord.toByteBuffer` serialises one back to bytes — the
Galil simulator uses this to construct `QR` responses without having to
hand-roll the binary layout.

`toParamSet` converts a parsed record into a CSW `Set[Parameter[?]]` for
event publishing. Per-axis parameters are name-mangled with the axis char
(e.g. `motorPosition_A`, `motorPosition_B`) since CSW does not currently
support structured parameter types.

## Testing

```bash
sbt "galil-io/test"
```

| Suite | Tests | Coverage |
|-------|------:|---------|
| GalilIoTest | 45 | `writeRaw`, `send` (single + compound), 80-char guard, `sendAndWaitForPrompt`, `downloadProgram`, `uploadProgram` (happy path, `?`-rejection detection, two-ack consume, read-timeout save/restore including the exception path), `chunkCompound`, response-parsing constants |

Tests use an in-package `StubGalilIo` that captures writes into a list and
serves scripted reads from a queue — no real socket I/O. The concrete TCP
and UDP transports are integration-tested separately by
`galil-simulator/GalilIoTests.scala` against a live simulator process.

`DataRecord` is exercised indirectly by the simulator's tests
(`GalilSimulatorActorTest`) which round-trip records through
`toByteBuffer` / `DataRecord(bytes)`.