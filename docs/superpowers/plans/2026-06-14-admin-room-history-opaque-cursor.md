# Admin Room History Opaque Cursor Task

## Goal

Admin room history pagination should use the same opaque cursor strategy as admin keyword/global search.

The cursor must be based on the stable ordering tuple:

- `roomSeq`
- `createdAt`
- `messageId`

## Scope

- Convert `GET /admin/chat-rooms/{roomId}/messages` cursor from `Long` to opaque `String`.
- Keep public user room history cursor APIs unchanged in this task.
- Keep the existing admin search cursor response contract compatible.
- Add PostgreSQL indexes for the admin room history tuple order.
- Update OpenAPI, API reference, and the high-traffic design spec.

## Implementation Checklist

- [x] Add RED tests for admin room history opaque cursor decoding, response `nextCursor`, OpenAPI contract, and DDL indexes.
- [x] Generalize admin message cursor codec while preserving admin search cursor compatibility.
- [x] Decode room history cursor before repository access.
- [x] Encode room history `nextCursor` from the last visible message.
- [x] Align room history repository cursor predicate with `ORDER BY room_seq DESC, created_at DESC, message_id DESC`.
- [x] Add default and partition child indexes for room history cursor order.
- [x] Update admin API docs and design docs.

## Validation

Run before merging:

- `./gradlew :chat-domain:test --tests com.chat.domain.dto.AdminMessageSearchCursorCodecTest --no-daemon`
- `./gradlew :chat-admin:test --tests com.chat.admin.controller.AdminChatControllerTest --no-daemon`
- `./gradlew :chat-persistence:test --tests com.chat.persistence.repository.AdminMessageRepositoryTest --tests com.chat.persistence.service.AdminChatServiceImplTest --no-daemon`
- `npm --prefix client-admin test`
- `node --test scripts/lib/openapiAdminContract.test.mjs scripts/lib/postgresDdl.test.mjs`
- `./gradlew test --no-daemon`
- `./gradlew check --no-daemon`

## Notes

- Admin search ordering remains `created_at DESC, room_seq DESC, message_id DESC`.
- Admin room history ordering is room-local and remains `room_seq DESC, created_at DESC, message_id DESC`.
- The two APIs share cursor payload fields, but each repository query must keep its cursor predicate aligned with its own `ORDER BY`.
