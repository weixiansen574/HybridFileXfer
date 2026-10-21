# Verification in protocol 401

The version handshake rejects protocol 400 peers. The PC and Android copies of
`core`, `nio` and `security` must remain identical; the desktop build checks this.
The AES-GCM and key-derivation domain identifiers remain unchanged. CRC32 detects
block corruption and does not replace authenticated transport.

## Data phase

Every block carries a CRC32 over the exact payload bytes, calculated directly
from the existing heap buffer. The sender writes its 24-byte header together:
command, file ID, byte offset, payload length, CRC32. The receiver checks CRC32
before writing, checkpointing or advancing the durable prefix. Its normal
acknowledgment is a combined 30-byte frame, including an empty error-string
length. This adds neither a normal-path round trip nor another file read.

A bad CRC returns a negative acknowledgment and requeues that block. Repeated
CRC failures retire the channel; the existing scheduler can use healthy channels.
Storage failures are distinct from checksum failures. File boundaries are
explicit even when a retry is stopped, so the next command cannot consume a
leftover block frame.

Full-file SHA-256 defaults off. Either endpoint can enable it for both; conflict
and other receiver-owned options retain their existing precedence. With SHA off,
complete files are published after data transfer. No deferred-verification claim
or lease is written on this path. Resume still compares the durable prefix, and
explicit incremental comparison still computes full content hashes before data
transfer to decide whether existing destination files can be skipped.

## Deferred verification and repair

With SHA enabled, each complete received file is sealed and its writer closes.
The session retains a small descriptor for each file, one session lease and
per-target claims, rather than keeping a file descriptor open for every file.
Target locks are reacquired for a verification window. A claim also protects the
target from a different source identity or another process while its lock is
temporarily released.

After the initial data phase, each endpoint hashes with two workers and a window
of at most eight files. Only the transfer owner reads and writes the control
channel. Poll messages exchange ready results and stop flags without waiting for
a hash inside a control handler. Local hash completion alone does not count as a
checked file: comparison or an explicit file error concludes it.

Matching digests publish the receiver's sealed generation. Unequal successful
digests mark a mismatch. Read errors, changed source metadata and publication
errors mark a file failure. At the end of a round, all mismatches are retransmitted
as a batch, then verified in another round. The destination chosen initially is
fixed across retries. A new staging generation preserves the previous complete
copy, so stopping a partial retry cannot destroy the only complete received copy.

Progress counts describe the current round; the final summary counts distinct
files, with separate verified, verification-failed, unchecked and retried counts.

## Stop and cleanup

Soft stop is available from the first verification phase through all repair
rounds. Both sides exchange stop flags through the final handshake. Complete
unchecked files may be published without overwriting an existing original;
known mismatches stay staged. Partial retries are never published as complete.
Only a successfully finished protocol can emit the soft-stop terminal callback.
Hard cancellation, lost connections and failure to stop hashing workers instead
produce an incomplete outcome and close the connection.

Android descriptors cross Binder without transferring file contents through
RPC. Client-side epochs reject late opens; explicit close acknowledgments keep
remote reader/writer reservations valid until the client has closed its copy.
Cancellation invalidates the epoch immediately, with potentially blocking remote
cleanup on a background worker. A new session cannot start while that cleanup
is unfinished. Tests cover delayed descriptors, concurrent destination claims,
repeated repair rounds and a second transfer over the same stopped-verification
connection. Real Binder, privileged IO modes and storage devices require device
acceptance in addition to those synthetic checks.
