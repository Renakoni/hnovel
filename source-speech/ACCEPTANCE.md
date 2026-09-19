# Online speech coverage

On 2026-09-19 UTC, **98 imported configurations across seven service families** passed the application's synthesis and playback path. This is the result of the tested batch, not a target number of sources or a count of independent engines.

| Service family | Application-verified configurations |
| --- | ---: |
| Volcengine / Doubao | 47 |
| Microsoft Translator recipes | 26 |
| AISpeech | 13 |
| Baidu | 8 |
| Qidian third-party services | 2 |
| iFlytek third-party service | 1 |
| Shipook / Yuzhi | 1 |
| Total | 98 |

[acceptance.json](acceptance.json) records each tested configuration's original name, service family, status, audio SHA-256, decoded duration and PCM RMS. Its sample indices are zero-based. The reference file happened to contain 103 entries; its SHA-256 identifies that sample only. Future batches should be chosen for useful, verifiable capabilities rather than a numerical quota. Configuration names come from the imported data and do not establish ownership of the endpoint or a count of unique voices.

The snapshot was assembled from the local acceptance records for [PR #256](https://github.com/Renakoni/hnovel/pull/256). `localEvidence` identifies the underlying local result file, not a public download. Credentials, original scripts, response tokens, book text and audio recordings are not included. This file is an acceptance record, not an importable voice pack.

## What passed

Each `app_verified` entry completed all of the following on the dedicated API 35 emulator:

1. Import the original definition and use the production `SpeechSynthesizers` HTTP path.
2. Obtain audio through the isolated runtime or the audited Microsoft adapter, and inspect its track with `MediaExtractor`.
3. Observe `Playing` and completion through production `ExoSpeechPlayback` / ExoPlayer.
4. Compare the collected audio's SHA-256 with the application result.
5. Decode the entire audio file with ffmpeg, with no decoding error, and check non-silent signed 16-bit mono PCM (RMS greater than 10).

This verifies the configured/default voice used for the sample. It does not verify every alternative in a selector, subjective voice quality, or long-duration stability. An earlier protocol-only probe passed 99 entries, including the AISpeech selection recipe at index 27. That result is deliberately not counted as 99 application-compatible configurations.

The bulk run took place during PR #256 development. After the final session-lifetime changes, Microsoft was separately checked on API 24 and API 35 with three consecutive syntheses and playback of the final segment. The final core also passed the permanent device and minified UI regressions. The batch should not be represented as a fresh rerun of all entries on every subsequent commit.

### Microsoft

All 26 recipes passed through the audited Translator adapter. The original signature envelope required correction. The two supported forms are fixed voice and header-selected voice; edited script structures are not silently treated as the same protocol. Signing material comes from the user's import.

The header-selected `en-US-JennyNeural` voice also passed at 1.0x and 1.5x, producing 5.256 and 3.528 seconds of audio respectively. These results do not cover Azure subscription-key REST or Edge WebSocket authentication.

### Network and website permissions

Seven AISpeech entries initially failed with network errors or timeouts, then passed in the application with Clash set to DIRECT. The original `rule` mode was restored and verified after testing.

Shipook's HTTPS destination and the Qidian audio redirect required additional exact website grants. The successful runs added `https://pysq.shipook.com:443` and `https://qd-tts-mnm.yuewen.com:443` where needed. The application exposes denied origins through the selected voice's **Service websites** action so the user can explicitly add the required site. Permission failures are not evidence of a dead service.

## Entries outside the verified set

| Original index | Configuration | Verified boundary and disposition |
| ---: | --- | --- |
| 27 | AISpeech voice selector | Protocol probe and player passed, but the original selection script is unsupported in the application. It uses numeric login-header storage and a template inside a generated request. Retain as an explicit limitation; do not reopen template evaluation of generated book text merely to increase the count. |
| 1 | Next dynamic recipe | Its first configured endpoint still failed to connect with Clash DIRECT. Excluded for this batch; other candidate endpoints were not individually tested. |
| 20 | deno proxy | The tested default configuration still failed TLS with Clash DIRECT. Excluded. |
| 23 | Baidu recipe | The service returned `Invalid param pdt`. Excluded. |
| 0 | Aliyun | No user AppKey, AccessKeyId or AccessKeySecret was available. No authentication or synthesis request was made. Account setup and the recipe's unsupported dependencies need separate verification before claiming support. |

These outcomes complete the disposition of the reference sample. They are not prerequisites for shipping the verified set, and none is silently substituted with a different voice.

## Using an online voice

1. Open **Read aloud settings → Online voices → Import voices** and select your JSON configuration file. Single objects and lists are supported.
2. Review the names and service websites, then confirm the import. Import validates the definition; it does not perform the live acceptance checks above.
3. Select a voice and return to **Read aloud settings** to preview it. **Voice options** provides editing, service websites and removal.
4. Open a book and use **Listen to this book** in the reader toolbar. The existing media service owns playback, stop and the book's independent listening position.

New interface strings use English and the existing simplified/traditional Chinese resources. Provider configuration names remain user data. Switching to **System speech** restores installed-engine selection.

The app supports imported voices; it does not ship the tested credential-bearing reference asset or provide a shared public account. The current Microsoft compatibility path therefore does not establish zero-configuration default voice supply.

## Follow-up scope

[Issue #252](https://github.com/Renakoni/hnovel/issues/252) covers this dated coverage record and its conclusions. It has no fixed source-count target. Further service families should address an actual availability, voice-quality or maintenance need.

The broader [read-aloud roadmap (#56)](https://github.com/Renakoni/hnovel/issues/56) retains the report's separate work: an official cloud provider if default online supply is needed, an autonomous offline voice if that becomes a product commitment, individual external-engine/version compatibility, and the proposed two-hour continuous-playback acceptance. Those outcomes are not claimed by this batch. Search changes and additional UI languages are outside this work.
