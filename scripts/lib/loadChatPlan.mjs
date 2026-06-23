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

export function flattenChatMessages(frame) {
  if (frame?.type === 'CHAT_MESSAGE') {
    return [frame];
  }
  if (frame?.type === 'CHAT_MESSAGE_BATCH' && Array.isArray(frame.messages)) {
    return frame.messages;
  }
  return [];
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
