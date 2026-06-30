import React from 'react';
import type { ChatRoom, User } from '@/types/index.ts';
import ChatRoomList from '@/components/ChatRoomList.tsx';
import { ChatWindow } from '@/components/ChatWindow.tsx';
import EmptyChatState from '@/components/EmptyChatState.tsx';

interface ChatWorkspaceProps {
  currentUser: User;
  selectedChatRoom: ChatRoom | null;
  sessionToken: string | null;
  onChatRoomSelect: (chatRoom: ChatRoom) => void;
  onError: (message: string) => void;
  onSuccess: (message: string) => void;
}

const ChatWorkspace: React.FC<ChatWorkspaceProps> = ({
  currentUser,
  selectedChatRoom,
  sessionToken,
  onChatRoomSelect,
  onError,
  onSuccess,
}) => {
  return (
    <div className="flex w-full h-screen overflow-hidden">
      <ChatRoomList
        currentUser={currentUser}
        selectedChatRoom={selectedChatRoom || undefined}
        onChatRoomSelect={onChatRoomSelect}
        onError={onError}
      />
      {selectedChatRoom && sessionToken ? (
        <ChatWindow
          chatRoom={selectedChatRoom}
          currentUser={currentUser}
          sessionToken={sessionToken}
          onError={onError}
          onSuccess={onSuccess}
        />
      ) : (
        <EmptyChatState />
      )}
    </div>
  );
};

export default ChatWorkspace;
