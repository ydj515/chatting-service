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
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--assert-room-seq-order') {
      options.assertRoomSeqOrder = true;
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

export async function readJsonResponse(response, { method = 'GET', url }) {
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`${method} ${url} failed: ${response.status} ${text}`);
  }
  return text ? JSON.parse(text) : null;
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
