export function parseLoadChatArgs(argv) {
  const options = {
    room: 'hot',
    viewers: 3,
    messagesPerSec: 100,
    durationSeconds: 30,
    metadataFile: null,
    drainWaitSeconds: 2,
    minReceivedRatio: 0,
    assertRoomSeqOrder: false,
    takeoverDeliverySummary: false,
    summaryMode: 'messages',
    label: null,
    seedRoomShards: null,
    minAcceptedRatio: 0,
    senders: 1,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--assert-room-seq-order') {
      options.assertRoomSeqOrder = true;
      continue;
    }
    if (arg === '--takeover-delivery-summary') {
      options.takeoverDeliverySummary = true;
      continue;
    }

    const value = argv[index + 1];
    if (value === undefined) {
      throw new Error(`Missing value for ${arg}`);
    }
    index += 1;

    if (arg === '--room') {
      options.room = value;
    } else if (arg === '--viewers') {
      options.viewers = positiveInteger(value, arg);
    } else if (arg === '--messages-per-sec') {
      options.messagesPerSec = positiveInteger(value, arg);
    } else if (arg === '--duration') {
      options.durationSeconds = positiveInteger(value, arg);
    } else if (arg === '--metadata-file') {
      options.metadataFile = value;
    } else if (arg === '--drain-wait') {
      options.drainWaitSeconds = positiveInteger(value, arg);
    } else if (arg === '--min-received-ratio') {
      options.minReceivedRatio = ratio(value, arg);
    } else if (arg === '--summary-mode') {
      if (!['messages', 'counts'].includes(value)) {
        throw new Error('--summary-mode must be messages or counts');
      }
      options.summaryMode = value;
    } else if (arg === '--label') {
      options.label = value;
    } else if (arg === '--seed-room-shards') {
      options.seedRoomShards = positiveInteger(value, arg);
    } else if (arg === '--min-accepted-ratio') {
      options.minAcceptedRatio = ratio(value, arg);
    } else if (arg === '--senders') {
      options.senders = positiveInteger(value, arg);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  return options;
}

export function assertRoomSeqOrder(messages) {
  let previous = null;
  for (const message of messages) {
    if (typeof message.roomSeq !== 'number') {
      throw new Error('Message is missing numeric roomSeq');
    }
    if (previous !== null && message.roomSeq < previous) {
      throw new Error(`roomSeq order violated: ${message.roomSeq} came after ${previous}`);
    }
    previous = message.roomSeq;
  }
}

export function assertMinimumReceived(receivedSamples, sent, minReceivedRatio) {
  if (minReceivedRatio <= 0) {
    return;
  }
  const minimum = Math.ceil(sent * minReceivedRatio);
  receivedSamples.forEach((messages, index) => {
    if (messages.length < minimum) {
      throw new Error(
        `viewer ${index} received only ${messages.length}/${sent}; minimum ratio ${minReceivedRatio} requires ${minimum}`,
      );
    }
  });
}

export function assertMinimumReceivedCounts(receivedCounts, sent, minReceivedRatio) {
  if (minReceivedRatio <= 0) {
    return;
  }
  const minimum = Math.ceil(sent * minReceivedRatio);
  receivedCounts.forEach((received, index) => {
    if (received < minimum) {
      throw new Error(
        `viewer ${index} received only ${received}/${sent}; minimum ratio ${minReceivedRatio} requires ${minimum}`,
      );
    }
  });
}

export function assertMinimumAcceptedMessages(senderSummary, targetMessages, minAcceptedRatio) {
  if (minAcceptedRatio <= 0) {
    return;
  }
  const accepted = senderSummary?.accepted;
  if (!Number.isInteger(accepted) || accepted < 0) {
    throw new Error('sender accepted count is missing');
  }
  const minimum = Math.ceil(targetMessages * minAcceptedRatio);
  if (accepted < minimum) {
    throw new Error(
      `sender accepted only ${accepted}/${targetMessages}; minimum ratio ${minAcceptedRatio} requires ${minimum}`,
    );
  }
}

export function flattenChatMessages(frame) {
  if (frame?.type === 'CHAT_MESSAGE') {
    return [frame];
  }
  if (frame?.type === 'CHAT_MESSAGE_BATCH' && Array.isArray(frame.messages)) {
    return frame.messages;
  }
  return [];
}

export function createViewerMessageCollector({ retainMessages, sampleLimit = 3 }) {
  const records = new Map();
  return {
    addViewer(userId) {
      records.set(userId, {
        userId,
        received: 0,
        samples: [],
        messages: retainMessages ? [] : null,
      });
    },
    record(userId, messages) {
      const record = records.get(userId);
      if (!record) {
        return;
      }
      record.received += messages.length;
      for (const message of messages) {
        if (record.samples.length < sampleLimit) {
          record.samples.push(message);
        }
      }
      if (record.messages) {
        record.messages.push(...messages);
      }
    },
    recordCount(userId, count) {
      const record = records.get(userId);
      if (!record || count <= 0) {
        return;
      }
      record.received += count;
    },
    receivedCounts() {
      return [...records.values()].map((record) => record.received);
    },
    receivedSamples() {
      return [...records.values()].map((record) => record.messages);
    },
    sampleSummary() {
      return [...records.values()].map(({ userId, received, samples }) => ({
        userId,
        received,
        samples,
      }));
    },
  };
}

export function createSenderAckTracker() {
  const pending = new Set();
  const acceptedIds = new Set();
  let attempted = 0;
  let flushed = 0;
  let accepted = 0;
  let unknownAccepted = 0;
  let duplicateAccepted = 0;

  return {
    recordAttempt(clientMessageId) {
      attempted += 1;
      pending.add(clientMessageId);
    },
    recordFlush() {
      flushed += 1;
    },
    recordAccepted(clientMessageId) {
      if (typeof clientMessageId !== 'string' || clientMessageId.length === 0) {
        unknownAccepted += 1;
        return;
      }
      if (pending.delete(clientMessageId)) {
        accepted += 1;
        acceptedIds.add(clientMessageId);
      } else if (acceptedIds.has(clientMessageId)) {
        duplicateAccepted += 1;
      } else {
        unknownAccepted += 1;
      }
    },
    recordFrame(frame) {
      if (frame?.type !== 'MESSAGE_ACCEPTED') {
        return;
      }
      this.recordAccepted(frame.clientMessageId);
    },
    snapshot() {
      return {
        attempted,
        flushed,
        accepted,
        pendingAcks: pending.size,
        unknownAccepted,
        duplicateAccepted,
        acceptanceRatio: ratioOrZero(accepted, attempted),
      };
    },
  };
}

export function writeSocketBuffer(socket, buffer) {
  if (!socket || socket.destroyed) {
    return Promise.reject(new Error('WebSocket socket is closed'));
  }

  try {
    if (socket.write(buffer)) {
      return undefined;
    }
  } catch (error) {
    return Promise.reject(error);
  }

  return new Promise((resolve, reject) => {
    let settled = false;
    const cleanup = () => {
      socket.off?.('drain', onDrain);
      socket.off?.('error', onError);
      socket.off?.('close', onClose);
    };
    const finish = (callback, value) => {
      if (settled) {
        return;
      }
      settled = true;
      cleanup();
      callback(value);
    };
    const onDrain = () => finish(resolve);
    const onError = (error) => finish(reject, error);
    const onClose = () => finish(reject, new Error('WebSocket socket closed before drain'));

    socket.once('drain', onDrain);
    socket.once('error', onError);
    socket.once('close', onClose);
  });
}

export function senderIndexForAttempt(attempt, senderCount) {
  const safeAttempt = positiveInteger(attempt, 'attempt');
  const safeSenderCount = positiveInteger(senderCount, 'senderCount');
  return (safeAttempt - 1) % safeSenderCount;
}

export function acceptedClientMessageIdFromRawFrame(rawMessage) {
  if (!rawMessage.includes('"MESSAGE_ACCEPTED"')) {
    return null;
  }
  try {
    const frame = JSON.parse(rawMessage);
    return frame?.type === 'MESSAGE_ACCEPTED' && typeof frame.clientMessageId === 'string'
      ? frame.clientMessageId
      : null;
  } catch {
    return null;
  }
}

export function countChatMessagesInRawFrame(rawMessage) {
  return rawMessage.match(/"type"\s*:\s*"CHAT_MESSAGE"/g)?.length ?? 0;
}

export function redactSensitiveUrl(value) {
  const url = new URL(value.toString());
  for (const name of ['ticket', 'token']) {
    if (url.searchParams.has(name)) {
      url.searchParams.set(name, '[redacted]');
    }
  }
  return url.toString();
}

export function formatLoadStepError({ label, viewerIndex, step, method, url, cause }) {
  const prefix = label ? `[${label}] ` : '';
  const actor = viewerIndex === undefined ? '' : `viewer ${viewerIndex} `;
  return `${prefix}${actor}${step} ${method} ${redactSensitiveUrl(url)} failed: ${cause.message}`;
}

export function buildRoomShardSeedSql({ roomId, shardCount }) {
  const safeRoomId = positiveInteger(roomId, 'roomId');
  const safeShardCount = positiveInteger(shardCount, 'shardCount');
  return `
INSERT INTO room_storage_configs (
  room_id,
  hot_room_policy,
  current_shard_count,
  fanout_shard_count,
  auto_policy_enabled,
  updated_at
)
VALUES (
  ${safeRoomId},
  'HOT',
  ${safeShardCount},
  ${safeShardCount},
  true,
  now()
)
ON CONFLICT (room_id) DO UPDATE SET
  hot_room_policy = 'HOT',
  current_shard_count = GREATEST(room_storage_configs.current_shard_count, EXCLUDED.current_shard_count),
  fanout_shard_count = GREATEST(room_storage_configs.fanout_shard_count, EXCLUDED.fanout_shard_count),
  auto_policy_enabled = true,
  updated_at = now()
`;
}

export function buildRoomShardSeedCommand({ roomId, shardCount, env = process.env }) {
  return {
    command: 'docker',
    args: [
      'compose',
      'exec',
      '-T',
      'postgres',
      'psql',
      '-U',
      env.DB_USERNAME ?? 'chatuser',
      '-d',
      env.DB_NAME ?? 'chatdb',
      '-v',
      'ON_ERROR_STOP=1',
      '-c',
      buildRoomShardSeedSql({ roomId, shardCount }),
    ],
  };
}

export function buildLoadUsername(prefix, entropy) {
  const safePrefix = sanitizeUsernamePart(prefix).slice(0, 8) || 'load';
  const safeEntropy = sanitizeUsernamePart(entropy).slice(-10) || Date.now().toString(36).slice(-10);
  return `${safePrefix}_${safeEntropy}`.slice(0, 20);
}

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

export async function readJsonResponse(response, { method = 'GET', url }) {
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`${method} ${url} failed: ${response.status} ${text}`);
  }
  return text ? JSON.parse(text) : null;
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
      if (roomSeqOwners.has(roomSeq)) {
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

function positiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}

function ratio(value, name) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > 1) {
    throw new Error(`${name} must be a number between 0 and 1`);
  }
  return parsed;
}

function sanitizeUsernamePart(value) {
  return String(value).replace(/[^a-zA-Z0-9_]/g, '');
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
