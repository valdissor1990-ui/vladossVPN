# Hotfix: seed fallback candidates (Poland #104)

## Problem
Instant `selectScanned` never called `SetFallbackCandidates`.
Traffic validation failure retries the **same** server 3× then fatal-locks.

## Fix (3 lines in `AutoSwitcher.kt`)

### 1. `selectImmediate` — after `_rankedServers.value = ...`
```kotlin
connectionCoordinator.send(ConnectionEvent.SetFallbackCandidates(_rankedServers.value))
```

### 2. `selectScanned` — after `_rankedServers.value = ...`
```kotlin
connectionCoordinator.send(ConnectionEvent.SetFallbackCandidates(_rankedServers.value))
```

### 3. `rankInBackground` — after `_rankedServers.value = ranked`
```kotlin
connectionCoordinator.send(ConnectionEvent.SetFallbackCandidates(ranked))
```

## Verify in logs
After connect you must see:
`Fallback candidates configured: N (incoming=N ...)`
On failure:
`Fallback attempt 1 -> <other server>`
NOT three retries of the same name then trafficFatal.
