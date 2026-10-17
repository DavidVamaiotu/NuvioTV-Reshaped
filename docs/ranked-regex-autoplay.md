# Ranked regex autoplay (tester)

Settings → Playback → Stream selection → choose Regex → Ranked regex rules.
Add up to 20 rules. Move them up/down to set priority, then Save. No rules are
installed by default. Clearing and saving all rules restores the existing single
regex setting without changing it. Cancel discards edits. Settings are per profile,
using the existing profile-scoped preferences store.

## Selection contract

1. Existing source scope, allowed addons/plugins, and playable-source checks apply.
2. Each eligible source belongs to its earliest matching rule. Priority 1 is best.
3. Choose from the highest available tier. Sources in the same tier retain the
   existing addon/result order. Matching several lower rules does not add points.
4. With configured ranked rules, the single regex is inactive. No matching source,
   invalid configuration, or exhausted matching budget returns manual selection.
5. Explicit last-link reuse and binge-group continuity retain their existing
   precedence. Disable those options to always request fresh tier selection.
6. This ranks automatic selection, including next episode and TorBox background
   link preparation. It does not reorder the manual source screen or modify playback.

This selects the best tier among responses available at the existing autoplay
selection point. It waits for addons or the configured timeout as before. An instant
(0-second) timeout can select before a slower addon supplies a better source. Use a
longer timeout or wait-for-all to give those addons a chance. An already playing
source is never replaced when a later result arrives.

## Example hierarchy

| Priority | Pattern | Preference |
| --- | --- | --- |
| 1 | `(?s)^(?=.*\b(?:2160p|4k)\b)(?=.*\bremux\b)` | 4K remux |
| 2 | `\b(?:2160p|4k)\b` | Other 4K |
| 3 | `\b1080p\b` | 1080p fallback |

Rules are case insensitive and use Java-style regex syntax. `(?s)` lets `.` cross
line breaks in addon descriptions. These examples demonstrate preferences, not a
recommendation that a device or network can play every matching source. There is
no implicit quality, codec, language, size, or HDR policy; express those in rules.
If an unwanted term must be excluded everywhere, include the negative lookahead
in **every** fallback rule, for example `(?s)^(?!.*\b(?:cam|hdcam|telesync)\b).*1080p`.
`.*` as the last rule is an explicit fallback to any eligible source; it is never
added automatically.

Matching text includes addon name, source name/title/description, quality metadata,
filename hints, debrid filename/torrent name, URL, and torrent info hash. A metadata
match is a preference signal, not verification of the underlying video. Missing
metadata can only match through fields the addon actually supplied.

## Isolation and limits

- `core/streams/ranking/RankedRegexRules.kt` is a generic synchronous ranking engine.
  It knows nothing about Android, Stream, repositories, DI, or player state.
- `StreamRegexRanking.kt` is the only Stream-to-text adapter.
- `RankedRegexSettingsCodec.kt` stores ordered JSON without losing regex escapes.
- `RankedRegexDialog.kt` owns the editor. Existing settings and selector call sites
  provide small hooks. No changes to renderers, resize, audio, or subtitle sync.
- The pinned `com.github.florianingerl.util:regex:1.1.11` matcher is private to this
  feature. It provides a Java matcher with CharSequence access on Android. The
  Android platform matcher converts input to String, defeating a read-count guard.
  No plugin is registered; the legacy regex still uses the platform matcher.
- Limit: 20 rules, 2048 characters per rule, 32768 characters per candidate text,
  and 10 million character reads across a ranking pass. Budget/stack exhaustion
  fails the whole pass to manual; it cannot accidentally prefer a lower tier.
- No migration or overwrite of the old regex. Invalid saved JSON remains an invalid
  ranked configuration until edited or cleared, rather than silently enabling a
  different automatic selection policy.

## Research and validation

AIOStreams documents two distinct approaches: first applicable preferred rules and
summed scores from ranked rules. This prototype uses strict ordered tiers because
that directly answers the requested hierarchy; cumulative scores can let multiple
lower preferences outweigh the intended first preference.

Primary references:

- https://docs.aiostreams.viren070.me/guides/scored-sorting/
- https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/stream.md
- https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/regex/Pattern.html
- https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-compose-integration-release/android-34/java/util/regex/Matcher.java
- https://github.com/florianingerl/com.florianingerl.util.regex

26 focused JVM tests cover global tier choice, stable ties, late better candidates,
fallback/no match, explicit catch-all, lookaheads, inline flags, source eligibility,
playability, filename metadata, continuity, old-mode behavior, read-limit abort,
1500+ candidates, invalid configuration, and ordered escaped JSON round trips.
The tester release workflow also executes these against the full Android project,
then builds signed APKs and checks in-place update compatibility. Physical remote
navigation and real addon metadata should be exercised by testers.
