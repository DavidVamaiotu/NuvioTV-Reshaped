# Automatic source startup recovery

Enable **Automatically try another source** in **Nuvio Reshaped** settings. It is off by default and uses the existing **First Source** or **Regex** autoplay preference, including ranked regex priorities and source filters. Manual selection and external players do not activate recovery. Turning it off stops active startup recovery. The toggle is included in Reshaped's TV settings sync.

Internal automatic playback gets up to three distinct source attempts, including the initial source. Each attempt has a 15-second deadline, with a 45-second overall budget. URL resolution and playback share that budget; rebuilding the same player or switching its engine does not reset it. The deliberate next-episode countdown is excluded.

The existing autoplay selector chooses every retry after attempted source identities are excluded. Ranked regex exhausts higher priorities first and keeps existing order for ties. First Source keeps normal source order. Legacy regex, addon/plugin filters, playability, and binge-only preferences retain their existing semantics. No unmatched stream is used as a last resort. File identities use provider/addon, hash and file index when available; exact URLs are also excluded.

Playback success ends recovery. Pausing, seeking, manually switching a source/engine, opening the source/episode picker, leaving the foreground, and disposal cancel it. There is no mid-playback source switching. Exhaustion opens the existing source picker. Dismissing it without choosing a source restores the normal retry/back error screen. External players, live TV and cloud-library playback do not use this recovery path. P2P must already be enabled before a fallback torrent can start.

Only failed startup can trigger an additional source search, bounded to five seconds, when the original snapshot has no untried eligible source. There are no parallel playback probes or new background services. Matching stays on the worker dispatcher. A single foreground coroutine samples startup state every 500 ms and stops once its job is done. Automatic frame-rate preflight is capped at three seconds within the attempt; manual playback keeps its existing budget.

Implementation boundaries:

- `core/player/recovery/StartupSourceRecovery.kt`: pure deadline/attempt decisions.
- `AutoSourceRecoveryPlan.kt`: immutable selection snapshot calling the existing selector.
- `PrepareAutoSource.kt`: cancellable URL resolution, preserving deadlines across navigation.
- `AutoSourceRecoveryHandoff.kt`: bounded, expiring, single-use in-memory navigation tokens, checked against profile and video. Process recreation safely falls back to ordinary playback.
- `ui/screens/player/PlayerAutoSourceRecovery.kt`: player adapter, cleanup and existing source switching.

The JVM regression suite covers deadlines, cancellation, resolution-to-player handoff, attempt limits, ordering, filters, duplicate identities and token isolation. The dedicated workflow compiles the Android app and runs recovery, ranking and existing autoplay selection tests without publishing a release. TV lifecycle, slow CDN and remote-control scenarios still require device testing.
