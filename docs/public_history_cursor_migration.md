# Public Room History Cursor Migration

## Summary

Public room history pagination now supports opaque cursor tokens while preserving legacy numeric cursors for existing clients.

New clients should use:

- request query parameter: `cursorToken`
- response fields: `nextCursorToken`, `prevCursorToken`

Legacy clients may continue to use:

- request query parameter: `cursor`
- response fields: `nextCursor`, `prevCursor`

## Ordering

Room history remains ordered by room sequence:

```text
roomSeq DESC, createdAt DESC, messageId DESC
```

The opaque token encodes:

- `createdAt`
- `roomSeq`
- `messageId`

Clients must treat the token as opaque and pass it back unchanged.

## Precedence

`cursorToken` wins over numeric `cursor` when both are supplied.

In plain terms: cursorToken wins over numeric cursor.

This lets migrated clients safely keep sending old numeric cursor values during rollout while the server follows the opaque token.

## Deprecation Window

The legacy numeric cursor remains accepted for `2 releases or 30 days after public client migration`, whichever is longer.

After the deprecation window:

- `cursorToken` remains the supported pagination cursor.
- `nextCursorToken` and `prevCursorToken` remain the supported response cursors.
- numeric `cursor`, `nextCursor`, and `prevCursor` may be removed from a future API version.

## Rollback

Rollback is supported during the migration window:

- legacy numeric cursor remains accepted by the server.
- server responses include both numeric cursors and opaque cursor tokens.
- a public client can roll back to numeric cursor pagination without a server rollback.

## Caveats

> - Numeric cursors and opaque cursors must point to the same message boundary while both are returned.
> - Clients must not decode or construct cursor tokens.
> - If both cursor formats are sent, any mismatch is resolved by using `cursorToken`.
> - Removing numeric cursor fields requires confirming public client rollout completion and the deprecation window expiry.
