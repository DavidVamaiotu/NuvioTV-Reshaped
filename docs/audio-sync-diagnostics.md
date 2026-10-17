# AudioSync diagnostics

Enable **Show AudioSync statistics** to see unique seconds of analysed audio,
approximate speech activity, and why timing has not been confirmed. Non-English
audio uses speech detection only; the English recognizer is intentionally idle.
Speech activity is the sum of VAD probabilities, not a transcription or a count
of seconds known with certainty to contain dialogue.

The exported AudioSync log includes the app version, a snapshot when the state
changes, a heartbeat every 15 seconds, and a final decoder/evidence snapshot when
the session stops. Capture the separate AutoSync debug log for embedded subtitle
discovery or extraction failures.

| Log field | Meaning |
| --- | --- |
| `analysed`, `speechActivity` | Unique media duration analysed and probability-weighted speech activity. Overlapping playback/capture analysis replaces earlier evidence. |
| `analysedThroughMs` | End of the latest known media frame; sampled spots can leave gaps before it. |
| `input`, `decoded`, `playback` | Compressed samples offered, PCM duration decoded from them, and PCM duration received from the playback tap. |
| `worker`, `queue`, `lastPcm`, `mediaMs` | Decoder worker state, queued bytes, time since last PCM, and that PCM's media timestamp. |
| `captureStarts`, `playbackStarts` | Analysis starts/restarts on each path. Frequent starts can indicate timestamp discontinuities; each start needs VAD warm-up. |
| `droppedInput`, `droppedPlayback` | Dropped compressed samples and playback buffers; queue overflow or unavailable decoder input can prevent continuous analysis. |
| `matcher`, `matcherElapsedMs`, `lastMatchRunMs` | Alignment worker state, time in the current queued/running job, and duration of its last completed job. |
| `waiting`, `candidate` | Current confirmation blocker and the latest candidate's offset, scale, score, prominence, cue count, and speech activity. |
| `sampling` | Sampling progress, or the gate preventing it (including unknown duration, short titles, and insufficient analysed audio). |

Decoder counters cover the decoder's lifetime, including audio captured before a
subtitle session. Timeline counters cover the current source's retained evidence.
Large input/output totals do not establish that those samples produced usable
speech evidence; compare them with `analysed` and the restart counts.

Interpret several consecutive heartbeats while playback is advancing:

- No input or playback PCM: investigate capture/tap routing and track selection.
- Input grows but no PCM appears: investigate the secondary decoder and its queue.
- PCM grows but analysed duration does not: investigate timestamps, repeated
  analyser restarts, or speech analysis failing to complete.
- Analysed duration grows but activity is flat: inspect the speech detector input;
  silence or music may also leave no useful variation.
- Candidates exist but remain unconfirmed: inspect competing peaks, dialogue
  overlap, and agreement between the two halves. Anime openings, translated
  subtitle segmentation, different edits, or offsets beyond the +/-60s search
  range can prevent a reliable match.

These are diagnostic leads, not automatic root-cause verdicts. The additional
metadata does not relax matching thresholds or force a timing correction.

Alternative subtitles are not permanently pruned on weak speech-activity scores
or reduced to a top-four shortlist. A bounded batch is retried as evidence grows;
only a confirmed match wins. Duplicate, unparsable, or clearly incompatible-length
files are still excluded. This does not relax the confidence required to apply timing.
