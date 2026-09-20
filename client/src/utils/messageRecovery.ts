import type { Message } from '@/types/index.ts';
import { mergeMessages } from '@/utils/messageEvents.ts';

export const recoverMessages = async (
  previous: Message[],
  latest: () => Promise<Message[]>,
  gap: (afterSeq: number, limit: number) => Promise<Message[]>,
): Promise<Message[]> => {
  let recovered: Message[] = [];
  let cursor = previous.reduce((max, message) => Math.max(max, message.roomSeq ?? message.sequenceNumber), 0);
  // Bound reconnect work to the live feed budget; latest history also repairs delayed writer visibility.
  if (cursor > 0) {
    for (let page = 0; page < 10; page++) {
      const batch = await gap(cursor, 100);
      recovered = mergeMessages(recovered, batch);
      const next = batch.reduce((max, message) => Math.max(max, message.roomSeq ?? message.sequenceNumber), cursor);
      if (batch.length < 100 || next <= cursor) break;
      cursor = next;
    }
  }
  return mergeMessages(recovered, await latest());
};
