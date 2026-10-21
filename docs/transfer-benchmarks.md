# Local transfer benchmarks

Build the current source with `python tools/build_desktop.py --test`, then run:

```text
python tools/benchmark_transfer.py --size-mib 64 --small-files 200 --small-kib 16 --channels 1,2,4 --repetitions 3
python tools/benchmark_transfer.py --size-mib 64 --small-files 200 --small-kib 16 --channels 1,2,4 --repetitions 3 --sha256 --output .build/benchmarks/transfer-sha256.csv
```

The tool generates its own local random files, transfers them over encrypted loopback channels,
and deletes only its temporary benchmark directory. CSV results are written to
`.build/benchmarks/transfer.csv`. The benchmark is outside automatic test discovery and does not
upload anything. Set `JAVA_HOME` to an installed JDK if the system Java launcher is unsuitable.

Each result includes transfer, CRC32 block checks, durable checkpoints and final commit.
With `--sha256`, timing also includes both endpoints' deferred full-file hashing and comparisons.
The CSV records whether SHA-256 was enabled. Single and multiple channels run the same workloads.
Even repetitions reverse channel order to reduce simple order bias; operating-system caches are
not flushed. Make sure enough temporary disk space is available for the source and each output.

Loopback results measure software, encryption and local storage overhead. They cannot establish
phone USB/Wi-Fi speed, wireless interference, Android storage performance, or HDD throughput.
Repeat the same file sets on the actual target devices, in both directions, with single-channel
and combined-channel configurations before accepting a performance claim.

Protocol 401 defaults to no whole-file SHA-256 pass. Interrupted files retain only a checkpointed
prefix for reuse, after comparing that prefix to the current source. Explicit strict incremental
comparison still hashes source files before transfer to compare existing destination candidates;
equal sizes never prove content equality.

## CRC32 buffer cost

`tools/java/BlockChecksumBenchmark.java` measures CRC32 on existing 1 MiB heap buffers
with a 64 MiB working set. It does not copy data or read files. Compile and run it explicitly
with a JDK; it is not a timing-sensitive test or part of normal builds.

This isolates one endpoint's checksum CPU/memory cost. It excludes encryption,
disk, synchronization and network I/O. Measure whole-transfer throughput on target
hardware before drawing performance conclusions.
