import { create } from 'zustand';
import { appConfig } from '@/config/appConfig.ts';
import type { ChatRoom, LoginResponse, Notification, User } from '@/types/index.ts';

const STORAGE_KEYS = {
  USER: 'chat_current_user',
  SESSION_TOKEN: 'chat_session_token',
  SESSION_EXPIRES_AT: 'chat_session_expires_at',
  SELECTED_ROOM: 'chat_selected_room',
} as const;

const parseSessionExpiryMillis = (expiresAt: string): number => {
  const hasTimeZone = /(?:z|[+-]\d{2}:\d{2})$/i.test(expiresAt);
  const normalizedExpiresAt = hasTimeZone ? expiresAt : `${expiresAt}Z`;
  const parsed = Date.parse(normalizedExpiresAt);
  return Number.isFinite(parsed) ? parsed : 0;
};

const clearStoredAuth = (storage: Storage) => {
  storage.removeItem(STORAGE_KEYS.USER);
  storage.removeItem(STORAGE_KEYS.SESSION_TOKEN);
  storage.removeItem(STORAGE_KEYS.SESSION_EXPIRES_AT);
  storage.removeItem(STORAGE_KEYS.SELECTED_ROOM);
};

interface ChatStore {
  currentUser: User | null;
  sessionToken: string | null;
  selectedChatRoom: ChatRoom | null;
  notifications: Notification[];
  isInitializing: boolean;
  lastErrorTime: number;
  hydrateAuth: (storage: Storage) => void;
  login: (response: LoginResponse, storage: Storage) => void;
  logout: (storage: Storage) => void;
  selectChatRoom: (chatRoom: ChatRoom, storage: Storage) => void;
  addNotification: (notification: Notification) => void;
  removeNotification: (id: string) => void;
  setLastErrorTime: (time: number) => void;
}

export const useChatStore = create<ChatStore>((set, get) => ({
  currentUser: null,
  sessionToken: null,
  selectedChatRoom: null,
  notifications: [],
  isInitializing: true,
  lastErrorTime: 0,

  hydrateAuth: (storage) => {
    try {
      const savedUser = storage.getItem(STORAGE_KEYS.USER);
      const savedToken = storage.getItem(STORAGE_KEYS.SESSION_TOKEN);
      const savedTokenExpiresAt = storage.getItem(STORAGE_KEYS.SESSION_EXPIRES_AT);
      const savedRoom = storage.getItem(STORAGE_KEYS.SELECTED_ROOM);

      if (
        savedUser &&
        savedToken &&
        savedTokenExpiresAt &&
        parseSessionExpiryMillis(savedTokenExpiresAt) > Date.now()
      ) {
        set({
          currentUser: JSON.parse(savedUser),
          sessionToken: savedToken,
          selectedChatRoom: savedRoom ? JSON.parse(savedRoom) : null,
          isInitializing: false,
        });
        return;
      }
    } catch (error) {
      console.error('Failed to load user from storage:', error);
      try {
        clearStoredAuth(storage);
      } catch (clearError) {
        console.error('Failed to clear stored user:', clearError);
      }
    }

    set({
      currentUser: null,
      sessionToken: null,
      selectedChatRoom: null,
      isInitializing: false,
    });
  },

  login: (response, storage) => {
    try {
      storage.setItem(STORAGE_KEYS.USER, JSON.stringify(response.user));
      storage.setItem(STORAGE_KEYS.SESSION_TOKEN, response.sessionToken);
      storage.setItem(STORAGE_KEYS.SESSION_EXPIRES_AT, response.expiresAt);
    } catch (error) {
      console.error('Failed to save user to storage:', error);
    }

    set({
      currentUser: response.user,
      sessionToken: response.sessionToken,
    });
  },

  logout: (storage) => {
    try {
      clearStoredAuth(storage);
    } catch (error) {
      console.error('Failed to clear user from storage:', error);
    }

    set({
      currentUser: null,
      sessionToken: null,
      selectedChatRoom: null,
      notifications: [],
    });
  },

  selectChatRoom: (chatRoom, storage) => {
    try {
      if (get().currentUser) {
        storage.setItem(STORAGE_KEYS.SELECTED_ROOM, JSON.stringify(chatRoom));
      }
    } catch (error) {
      console.error('Failed to save selected room to storage:', error);
    }

    set({ selectedChatRoom: chatRoom });
  },

  addNotification: (notification) => {
    set((state) => {
      const isDuplicate = state.notifications.some((item) =>
        item.type === notification.type &&
        item.title === notification.title &&
        Date.now() - item.timestamp < appConfig.notification.dedupWindowMs
      );

      if (isDuplicate) {
        return state;
      }

      return {
        notifications: [notification, ...state.notifications.slice(0, 2)],
      };
    });
  },

  removeNotification: (id) => {
    set((state) => ({
      notifications: state.notifications.filter((notification) => notification.id !== id),
    }));
  },

  setLastErrorTime: (time) => {
    set({ lastErrorTime: time });
  },
}));
