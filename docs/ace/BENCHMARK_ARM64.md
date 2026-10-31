# ARM64 FFI Benchmark Protocol

- Device: arm64-v8a, Android API >= 30, [device model TBD]
- Executor: Stage B first task (P7=B default) — provisional UniFFI
- Operations: no-op FFI, 1 KB payload, batched 100 items
- Iterations: warmup 100, measure 1000 per operation
- Metrics: median µs/op, p95 µs/op, alloc bytes if measurable
- Compare: UniFFI single, UniFFI batched, optional raw JNI, Kotlin baseline
- Result table + verdict: UniFFI suitable / not suitable for Stage B
- If unsuitable: redo FFI layer; revisit ADR-002

**Note:** Full device benchmark requires physical hardware. Until run,
FFI choice remains **provisional UniFFI**.
