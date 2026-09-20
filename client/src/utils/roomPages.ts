import type { ChatRoom, PageResponse } from '@/types/index.ts';

export const loadRoomPages = async (
  fetchPage: (page: number) => Promise<PageResponse<ChatRoom>>,
): Promise<ChatRoom[]> => {
  const rooms = new Map<number, ChatRoom>();
  for (let page = 0; ; page++) {
    const result = await fetchPage(page);
    result.content.forEach(room => rooms.set(room.id, room));
    if (result.last || result.content.length === 0) return [...rooms.values()];
  }
};
