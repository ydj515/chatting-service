# Phase 7 Fanout Takeover Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the owner kill takeover smoke summary so raw delivery inversions are diagnostic while client-visible dedupe/render failures remain release blocking.

**Architecture:** `scripts/lib/loadChatPlan.mjs` owns pure delivery-summary analysis. `scripts/load-chat.mjs` emits the optional `takeoverDelivery` summary. `scripts/phase6-fanout-takeover-smoke.mjs` includes that summary in its final JSON and fails only when the client-visible release gate is blocking.

**Tech Stack:** Node.js ESM scripts, `node:test`, Docker Compose smoke scripts, Markdown docs.

---

### Task 1: Add failing load-chat summary contract tests

**Files:**
- Modify: `scripts/lib/loadChatPlan.test.mjs`
- Modify later: `scripts/lib/loadChatPlan.mjs`

- [ ] **Step 1: Write the failing tests**

Add `summarizeTakeoverDelivery` to the import list:

```js
import {
  assertMinimumReceived,
  assertRoomSeqOrder,
  buildLoadUsername,
  flattenChatMessages,
  parseLoadChatArgs,
  readJsonResponse,
  summarizeTakeoverDelivery,
} from './loadChatPlan.mjs';
```

Append these tests:

```js
test('parseLoadChatArgs enables takeover delivery summary without raw order assertion', () => {
  const options = parseLoadChatArgs(['--takeover-delivery-summary']);

  assert.equal(options.takeoverDeliverySummary, true);
  assert.equal(options.assertRoomSeqOrder, false);
});

test('summarizeTakeoverDelivery treats duplicate replay as diagnostic when client-visible output is stable', () => {
  const summary = summarizeTakeoverDelivery([
    [
      { messageId: 'm1', roomSeq: 1 },
      { messageId: 'm2', roomSeq: 2 },
      { messageId: 'm3', roomSeq: 3 },
      { messageId: 'm2', roomSeq: 2 },
    ],
  ], { sent: 3, minReceivedRatio: 1 });

  assert.equal(summary.releaseBlocking, false);
  assert.equal(summary.aggregate.rawInversionCount, 1);
  assert.equal(summary.aggregate.duplicateReplayCount, 1);
  assert.equal(summary.aggregate.firstSeenLateDeliveryCount, 0);
  assert.equal(summary.viewers[0].clientVisible.uniqueCount, 3);
  assert.equal(summary.viewers[0].clientVisible.minReceivedSatisfied, true);
});

test('summarizeTakeoverDelivery marks first-seen late delivery as release blocking', () => {
  const summary = summarizeTakeoverDelivery([
    [
      { messageId: 'm1', roomSeq: 1 },
      { messageId: 'm3', roomSeq: 3 },
      { messageId: 'm2', roomSeq: 2 },
    ],
  ], { sent: 3, minReceivedRatio: 1 });

  assert.equal(summary.releaseBlocking, true);
  assert.equal(summary.aggregate.rawInversionCount, 1);
  assert.equal(summary.aggregate.duplicateReplayCount, 0);
  assert.equal(summary.aggregate.firstSeenLateDeliveryCount, 1);
  assert.equal(summary.viewers[0].releaseBlocking, true);
});

test('summarizeTakeoverDelivery marks unclassifiable payloads as release blocking', () => {
  const summary = summarizeTakeoverDelivery([
    [
      { messageId: 'm1', roomSeq: 1 },
      { roomSeq: 2 },
      { messageId: 'm3' },
    ],
  ], { sent: 3, minReceivedRatio: 1 });

  assert.equal(summary.releaseBlocking, true);
  assert.equal(summary.aggregate.missingMessageIdCount, 1);
  assert.equal(summary.aggregate.missingRoomSeqCount, 1);
});

test('summarizeTakeoverDelivery applies minimum receive ratio to unique client-visible messages', () => {
  const summary = summarizeTakeoverDelivery([
    [
      { messageId: 'm1', roomSeq: 1 },
      { messageId: 'm1', roomSeq: 1 },
    ],
  ], { sent: 2, minReceivedRatio: 1 });

  assert.equal(summary.releaseBlocking, true);
  assert.equal(summary.viewers[0].clientVisible.uniqueCount, 1);
  assert.equal(summary.viewers[0].clientVisible.minimumRequired, 2);
  assert.equal(summary.viewers[0].clientVisible.minReceivedSatisfied, false);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
node --test scripts/lib/loadChatPlan.test.mjs
```

Expected: FAIL because `summarizeTakeoverDelivery` is not exported and `takeoverDeliverySummary` is not parsed.

### Task 2: Implement takeover delivery summary analysis

**Files:**
- Modify: `scripts/lib/loadChatPlan.mjs`
- Test: `scripts/lib/loadChatPlan.test.mjs`

- [ ] **Step 1: Add the option default and parser branch**

In `parseLoadChatArgs`, add the default:

```js
takeoverDeliverySummary: false,
```

Handle the flag before value-consuming arguments:

```js
if (arg === '--takeover-delivery-summary') {
  options.takeoverDeliverySummary = true;
  continue;
}
```

- [ ] **Step 2: Add summary helpers**

Append these exports before `readJsonResponse`:

```js
export function summarizeTakeoverDelivery(receivedSamples, { sent, minReceivedRatio }) {
  const viewers = receivedSamples.map((messages, viewerIndex) => (
    summarizeViewerTakeoverDelivery(messages, { viewerIndex, sent, minReceivedRatio })
  ));
  const aggregate = viewers.reduce((acc, viewer) => ({
    rawReceived: acc.rawReceived + viewer.raw.received,
    rawInversionCount: acc.rawInversionCount + viewer.raw.inversionCount,
    duplicateReplayCount: acc.duplicateReplayCount + viewer.raw.duplicateReplayCount,
    firstSeenLateDeliveryCount: acc.firstSeenLateDeliveryCount + viewer.raw.firstSeenLateDeliveryCount,
    missingMessageIdCount: acc.missingMessageIdCount + viewer.raw.missingMessageIdCount,
    missingRoomSeqCount: acc.missingRoomSeqCount + viewer.raw.missingRoomSeqCount,
    clientVisibleUniqueCount: acc.clientVisibleUniqueCount + viewer.clientVisible.uniqueCount,
    clientVisibleDuplicateCount: acc.clientVisibleDuplicateCount + viewer.clientVisible.duplicateCount,
    roomSeqConflictCount: acc.roomSeqConflictCount + viewer.clientVisible.roomSeqConflictCount,
    releaseBlockingViewerCount: acc.releaseBlockingViewerCount + (viewer.releaseBlocking ? 1 : 0),
  }), {
    rawReceived: 0,
    rawInversionCount: 0,
    duplicateReplayCount: 0,
    firstSeenLateDeliveryCount: 0,
    missingMessageIdCount: 0,
    missingRoomSeqCount: 0,
    clientVisibleUniqueCount: 0,
    clientVisibleDuplicateCount: 0,
    roomSeqConflictCount: 0,
    releaseBlockingViewerCount: 0,
  });

  return {
    releaseBlocking: aggregate.releaseBlockingViewerCount > 0,
    viewers,
    aggregate,
  };
}

function summarizeViewerTakeoverDelivery(messages, { viewerIndex, sent, minReceivedRatio }) {
  const seenMessages = new Map();
  const roomSeqOwners = new Map();
  const raw = {
    received: messages.length,
    inversionCount: 0,
    duplicateReplayCount: 0,
    firstSeenLateDeliveryCount: 0,
    missingMessageIdCount: 0,
    missingRoomSeqCount: 0,
    maxRoomSeq: null,
  };
  const clientVisible = {
    uniqueCount: 0,
    duplicateCount: 0,
    roomSeqConflictCount: 0,
    minimumRequired: minimumReceived(sent, minReceivedRatio),
    minReceivedSatisfied: true,
    sorted: true,
  };

  for (const message of messages) {
    const messageId = stableMessageId(message);
    const roomSeq = numericRoomSeq(message);
    if (!messageId) raw.missingMessageIdCount += 1;
    if (roomSeq === null) raw.missingRoomSeqCount += 1;

    const duplicate = messageId ? seenMessages.has(messageId) : false;
    const late = roomSeq !== null && raw.maxRoomSeq !== null && roomSeq < raw.maxRoomSeq;
    if (late) raw.inversionCount += 1;
    if (duplicate) {
      raw.duplicateReplayCount += 1;
      clientVisible.duplicateCount += 1;
    } else if (late && messageId) {
      raw.firstSeenLateDeliveryCount += 1;
    }

    if (messageId && roomSeq !== null && !duplicate) {
      seenMessages.set(messageId, message);
      if (roomSeqOwners.has(roomSeq) && roomSeqOwners.get(roomSeq) !== messageId) {
        clientVisible.roomSeqConflictCount += 1;
      } else {
        roomSeqOwners.set(roomSeq, messageId);
      }
    }
    if (roomSeq !== null) {
      raw.maxRoomSeq = raw.maxRoomSeq === null ? roomSeq : Math.max(raw.maxRoomSeq, roomSeq);
    }
  }

  clientVisible.uniqueCount = seenMessages.size;
  clientVisible.minReceivedSatisfied = clientVisible.minimumRequired === 0
    || clientVisible.uniqueCount >= clientVisible.minimumRequired;
  clientVisible.sorted = raw.missingMessageIdCount === 0
    && raw.missingRoomSeqCount === 0
    && clientVisible.roomSeqConflictCount === 0;

  return {
    viewerIndex,
    releaseBlocking: raw.firstSeenLateDeliveryCount > 0
      || raw.missingMessageIdCount > 0
      || raw.missingRoomSeqCount > 0
      || clientVisible.roomSeqConflictCount > 0
      || !clientVisible.minReceivedSatisfied,
    raw: {
      ...raw,
      duplicateReplayRatio: ratioOrZero(raw.duplicateReplayCount, raw.received),
      firstSeenLateDeliveryRatio: ratioOrZero(raw.firstSeenLateDeliveryCount, raw.received),
    },
    clientVisible,
  };
}

function stableMessageId(message) {
  return typeof message?.messageId === 'string' && message.messageId.trim() ? message.messageId : null;
}

function numericRoomSeq(message) {
  return typeof message?.roomSeq === 'number' && Number.isFinite(message.roomSeq) ? message.roomSeq : null;
}

function minimumReceived(sent, minReceivedRatio) {
  if (minReceivedRatio <= 0) {
    return 0;
  }
  return Math.ceil(sent * minReceivedRatio);
}

function ratioOrZero(count, total) {
  return total > 0 ? count / total : 0;
}
```

- [ ] **Step 3: Run tests to verify green**

Run:

```bash
node --test scripts/lib/loadChatPlan.test.mjs
```

Expected: PASS.

### Task 3: Wire load-chat JSON output

**Files:**
- Modify: `scripts/load-chat.mjs`
- Modify: `scripts/lib/loadChatPlan.mjs`
- Test: `scripts/lib/loadChatPlan.test.mjs`

- [ ] **Step 1: Import the summary helper**

Update the import in `scripts/load-chat.mjs`:

```js
import {
  assertMinimumReceived,
  assertRoomSeqOrder,
  buildLoadUsername,
  flattenChatMessages,
  parseLoadChatArgs,
  readJsonResponse,
  summarizeTakeoverDelivery,
} from './lib/loadChatPlan.mjs';
```

- [ ] **Step 2: Build summary before printing JSON**

Replace the final `console.log(JSON.stringify({ ... }))` block with:

```js
  const takeoverDelivery = options.takeoverDeliverySummary
    ? summarizeTakeoverDelivery(receivedSamples, {
      sent,
      minReceivedRatio: options.minReceivedRatio,
    })
    : null;
  const summary = {
    ok: true,
    roomId: room.id,
    sent,
    viewers: options.viewers,
    receivedPerViewer: receivedSamples.map((messages) => messages.length),
    minReceivedRatio: options.minReceivedRatio,
    assertedRoomSeqOrder: options.assertRoomSeqOrder,
    takeoverDeliverySummary: options.takeoverDeliverySummary,
    ...(takeoverDelivery ? { takeoverDelivery } : {}),
  };

  console.log(JSON.stringify(summary, null, 2));
```

- [ ] **Step 3: Run syntax check**

Run:

```bash
node --check scripts/load-chat.mjs
```

Expected: PASS.

### Task 4: Connect takeover smoke to the summary gate

**Files:**
- Modify: `scripts/lib/phase6TakeoverSmokePlan.test.mjs`
- Modify: `scripts/lib/phase6TakeoverSmokePlan.mjs`
- Modify: `scripts/phase6-fanout-takeover-smoke.mjs`

- [ ] **Step 1: Write failing tests**

Add `buildTakeoverSmokeSummary` to the import list in `scripts/lib/phase6TakeoverSmokePlan.test.mjs`:

```js
  buildTakeoverSmokeSummary,
```

Replace the old `buildLoadChatArgs always verifies roomSeq order and minimum fanout receipt` expectation so it expects `--takeover-delivery-summary` and no `--assert-room-seq-order`:

```js
test('buildLoadChatArgs asks load-chat for takeover delivery summary and minimum fanout receipt', () => {
  assert.deepEqual(
    buildLoadChatArgs({
      room: 'phase6',
      viewers: 3,
      messagesPerSec: 20,
      durationSeconds: 20,
      drainWaitSeconds: 12,
      minReceivedRatio: 0.9,
    }),
    [
      '--room',
      'phase6',
      '--viewers',
      '3',
      '--messages-per-sec',
      '20',
      '--duration',
      '20',
      '--drain-wait',
      '12',
      '--min-received-ratio',
      '0.9',
      '--takeover-delivery-summary',
    ],
  );
});
```

Append:

```js
test('buildTakeoverSmokeSummary carries takeover delivery and flips ok on release blocking summary', () => {
  const summary = buildTakeoverSmokeSummary({
    killedContainer: {
      name: 'chatting-service-chat-worker-app-1-1',
      id: 'abcdef1234567890',
    },
    loadSummary: {
      roomId: 7,
      sent: 10,
      receivedPerViewer: [10, 11],
      minReceivedRatio: 0.9,
      assertedRoomSeqOrder: false,
      takeoverDelivery: {
        releaseBlocking: true,
        aggregate: { firstSeenLateDeliveryCount: 1 },
        viewers: [],
      },
    },
  });

  assert.equal(summary.ok, false);
  assert.equal(summary.killedContainer, 'chatting-service-chat-worker-app-1-1');
  assert.equal(summary.killedContainerId, 'abcdef123456');
  assert.equal(summary.takeoverDelivery.releaseBlocking, true);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
node --test scripts/lib/phase6TakeoverSmokePlan.test.mjs
```

Expected: FAIL because `buildTakeoverSmokeSummary` is not exported and load args still pass `--assert-room-seq-order`.

- [ ] **Step 3: Implement the plan helpers**

In `scripts/lib/phase6TakeoverSmokePlan.mjs`, change `buildLoadChatArgs()` to append `--takeover-delivery-summary` instead of `--assert-room-seq-order`.

Add:

```js
export function buildTakeoverSmokeSummary({ killedContainer, loadSummary }) {
  const releaseBlocking = loadSummary.takeoverDelivery?.releaseBlocking === true;
  return {
    ok: !releaseBlocking,
    killedContainer: killedContainer.name,
    killedContainerId: killedContainer.id.slice(0, 12),
    roomId: loadSummary.roomId,
    sent: loadSummary.sent,
    receivedPerViewer: loadSummary.receivedPerViewer,
    minReceivedRatio: loadSummary.minReceivedRatio,
    assertedRoomSeqOrder: loadSummary.assertedRoomSeqOrder,
    takeoverDeliverySummary: loadSummary.takeoverDeliverySummary === true,
    ...(loadSummary.takeoverDelivery ? { takeoverDelivery: loadSummary.takeoverDelivery } : {}),
  };
}
```

- [ ] **Step 4: Use helper in takeover smoke**

Import `buildTakeoverSmokeSummary` in `scripts/phase6-fanout-takeover-smoke.mjs`.

Replace the manual final JSON construction with:

```js
    const finalSummary = buildTakeoverSmokeSummary({
      killedContainer,
      loadSummary: summary,
    });
    console.log(JSON.stringify(finalSummary, null, 2));
    if (!finalSummary.ok) {
      throw new Error('takeover delivery release gate failed');
    }
```

- [ ] **Step 5: Run tests**

Run:

```bash
node --test scripts/lib/phase6TakeoverSmokePlan.test.mjs
```

Expected: PASS.

### Task 5: Add operator documentation and update slice index

**Files:**
- Create: `docs/phase7_fanout_takeover_summary.md`
- Modify: `docs/phase7_slices.md`
- Modify: `docs/superpowers/plans/2026-06-23-phase7-fanout-takeover-summary.md`

- [ ] **Step 1: Create the operator doc**

Create `docs/phase7_fanout_takeover_summary.md` with:

```markdown
# Phase 7 Fanout Takeover Delivery Summary

이 문서는 owner kill takeover smoke가 raw delivery diagnostic과 client-visible release gate를 어떻게 분리하는지 설명한다.

## 실행

```bash
node scripts/phase6-fanout-takeover-smoke.mjs \
  --room phase7-takeover-summary \
  --viewers 3 \
  --messages-per-sec 20 \
  --duration 20 \
  --kill-after 5 \
  --drain-wait 12
```

## Summary 필드

| 필드 | 의미 |
| --- | --- |
| `takeoverDelivery.aggregate.rawInversionCount` | raw 수신 순서에서 `roomSeq`가 뒤로 간 횟수 |
| `takeoverDelivery.aggregate.duplicateReplayCount` | 이미 본 `messageId`가 다시 온 횟수 |
| `takeoverDelivery.aggregate.firstSeenLateDeliveryCount` | 처음 보는 낮은 `roomSeq`가 뒤늦게 온 횟수 |
| `takeoverDelivery.aggregate.releaseBlockingViewerCount` | release blocking viewer 수 |
| `takeoverDelivery.viewers[].clientVisible.uniqueCount` | `messageId` dedupe 후 남은 메시지 수 |
| `takeoverDelivery.viewers[].clientVisible.minReceivedSatisfied` | unique message 수가 최소 수신 기준을 만족하는지 |

## 판정

- `releaseBlocking=false`: duplicate replay 같은 raw diagnostic은 있을 수 있지만 client-visible 결과는 안정적이다.
- `releaseBlocking=true`: first-seen late delivery, 판정 불가 payload, `roomSeq` 충돌, unique message 부족 중 하나가 발생했다.

## 복잡도

- 시간 복잡도: `O(T)`
- 공간 복잡도: `O(U)`

여기서 `T`는 모든 viewer가 받은 raw 메시지 수이고, `U`는 viewer별 고유 `messageId` 수다.

## 주의사항

> - owner kill/takeover 경로에서만 raw inversion을 diagnostic으로 완화한다.
> - steady-state load에서는 raw `roomSeq` 역전이 여전히 실패다.
> - duplicate replay는 수량과 비율을 보고 후속 metric 기준으로 승격할 수 있다.

## 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| client-visible gate | takeover flake를 사용자 영향 기준으로 분류한다 | summary 계산이 필요하다 | 기본 |
| raw order gate | 단순하다 | duplicate replay와 화면 흔들림을 구분하지 못한다 | 사용하지 않음 |

## 후속 질문

- duplicate replay 허용 한도를 어느 release gate에서 숫자로 고정할 것인가?
- actual client gap fill synthetic test를 reconnect load test에 포함할 것인가?
```
```

- [ ] **Step 2: Update slice index**

Update row 3 in `docs/phase7_slices.md`:

```markdown
| 3 | Fanout owner kill takeover summary 확장 | [설계](./superpowers/specs/2026-06-23-phase7-fanout-takeover-summary-design.md), [계획](./superpowers/plans/2026-06-23-phase7-fanout-takeover-summary.md), [운영 문서](./phase7_fanout_takeover_summary.md) | 구현 완료 |
```

### Task 6: Verification and commit

**Files:**
- All modified files

- [ ] **Step 1: Run focused tests**

Run:

```bash
node --test scripts/lib/loadChatPlan.test.mjs
node --test scripts/lib/phase6TakeoverSmokePlan.test.mjs
```

Expected: PASS.

- [ ] **Step 2: Run all Node script tests**

Run:

```bash
node --test scripts/lib/*.test.mjs
```

Expected: PASS.

- [ ] **Step 3: Run syntax checks**

Run:

```bash
node --check scripts/load-chat.mjs
node --check scripts/phase6-fanout-takeover-smoke.mjs
```

Expected: PASS.

- [ ] **Step 4: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 5: Run Compose smoke when services are available**

Run:

```bash
node scripts/phase6-fanout-takeover-smoke.mjs \
  --room phase7-takeover-summary \
  --viewers 3 \
  --messages-per-sec 10 \
  --duration 15 \
  --kill-after 5 \
  --drain-wait 12
```

Expected: final JSON includes `takeoverDelivery` and exits successfully when `releaseBlocking=false`. If local Compose is not running, record that runtime smoke was not executed.

- [ ] **Step 6: Commit implementation**

```bash
git add \
  docs/phase7_fanout_takeover_summary.md \
  docs/phase7_slices.md \
  docs/superpowers/plans/2026-06-23-phase7-fanout-takeover-summary.md \
  scripts/load-chat.mjs \
  scripts/lib/loadChatPlan.mjs \
  scripts/lib/loadChatPlan.test.mjs \
  scripts/phase6-fanout-takeover-smoke.mjs \
  scripts/lib/phase6TakeoverSmokePlan.mjs \
  scripts/lib/phase6TakeoverSmokePlan.test.mjs
git commit -m "feat: add phase7 fanout takeover summary"
```

---

## Self-Review

- Spec coverage: covers raw diagnostic summary, duplicate replay classification, first-seen late delivery release gate, client-visible dedupe/sort summary, takeover smoke output, docs, and slice index updates.
- Placeholder scan: no `TBD`, `TODO`, or vague implementation placeholders remain.
- Type consistency: option name is `takeoverDeliverySummary`; summary field is `takeoverDelivery`; release gate field is `releaseBlocking`.
