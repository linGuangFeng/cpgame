# Batch D capture privacy and evidence contract

This contract applies only to raw game IDs 58, 60, 1810, and 1830 for account alias
`abc224`.

## Browser dispatch

- Batch D no longer opens or reconnects any Chrome profile. All abc224 browser attempts are
  permanently stopped.
- Original evidence is accepted only through the stable abc222 rotation channel and must preserve
  its actually observed account alias. Backfilled evidence must use raw gids, pass the same
  credential rejection and History 1:1 gates, and must not be relabeled as an abc224 observation.

## Ephemeral credential boundary

- The current short-lived credential may exist only in the live browser execution memory.
- Immediately before sending it, obtain action-time confirmation naming the credential and
  destination `api.omgapibra.com`.
- The destination host is an exact allowlist entry; redirects, alternate hosts, URL embedding,
  shell environment variables, clipboard export, browser storage, files, logs, screenshots, and
  terminal output are forbidden.
- Request/response persistence occurs only after recursive rejection of credential-like object
  keys and removal of authorization/cookie headers and credential-bearing URL parameters.
- A redaction failure aborts the write. It must never fall back to masking an unknown secret.

## Evidence authority labels

- `ORIGINAL_AUTHORIZED_HTTP` means a redacted response obtained from the explicitly authorized
  in-memory request to `api.omgapibra.com`.
- `ORIGINAL_OFFICIAL_UI_MANUAL` means a paid Round started with the visible central Spin control
  and reconciled through the official UI. It must never be relabeled as authorized HTTP evidence.
- `OFFLINE_GENERATED_NOT_ORIGINAL` is permitted only for tooling tests and can never satisfy an
  original-site coverage target.
- If DNS resolution for `api.omgapibra.com` fails, do not bypass it. The fallback is at most 50
  manual central paid Spins with Auto Spin disabled.

## Complete Round and History

- One paid start plus every adjacent cascade, respin, free/reward Step, retrigger, and terminal
  transition is one Round.
- `spin-index.jsonl` contains exactly one row per paid start, keyed by `roundKey`.
- A Round is not complete until a legal terminal Step is durably referenced.
- History acceptance requires list visibility, detail click, detail observation, return to list,
  and an exact one-to-one `roundKey` match with every completed paid Round.
- Missing, duplicate, extra, unclickable, or non-returnable History rows fail acceptance.

The executable offline gates are `tools/batch-d-offline-audit.mjs` and
`tools/batch-d-round-ledger.mjs`. Dry-run mode performs no network access and writes no samples.
