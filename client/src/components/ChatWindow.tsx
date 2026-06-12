import React, { useState, useEffect, useRef, useCallback } from 'react';
import { ChatRoom, Message, User, WebSocketMessage } from '../types/index';
import { messageApi } from '../services/api.ts';
import { useWebSocket } from '../hooks/useWebSocket.ts';

interface ChatWindowProps {
  currentUser: User;
  sessionToken: string;
  chatRoom: ChatRoom;
  onError: (error: string) => void;
}

export const ChatWindow: React.FC<ChatWindowProps> = ({
  currentUser,
  sessionToken,
  chatRoom,
  onError,
}) => {
  const [messages, setMessages] = useState<Message[]>([]);
  const [messageInput, setMessageInput] = useState('');
  const [isLoadingMessages, setIsLoadingMessages] = useState(false);
  const [isTyping, setIsTyping] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const typingTimeoutRef = useRef<number>();

  // WebSocket 연결
  const {
    isConnected,
    lastMessage,
    sendMessage: sendWebSocketMessage,
    error: wsError,
  } = useWebSocket({
    sessionToken,
    onConnect: () => console.log('🔌 WebSocket 연결됨'),
    onDisconnect: () => console.log('🔌 WebSocket 연결 해제됨'),
    onError: (error) => console.error('🔌 WebSocket 에러:', error),
  });
  
  // WebSocket 메시지 도착 시 처리
  useEffect(() => {
    console.log('--- 📬 WebSocket 메시지 수신 ---', lastMessage);
    if (!lastMessage) return;

    // 'lastMessage'는 서버로부터 받은 다양한 타입의 메시지일 수 있으므로 any로 처리
    const wsMessage: any = lastMessage;

    console.log(`[디버그] 현재 방 ID: ${chatRoom.id} | 메시지 방 ID: ${wsMessage.chatRoomId}`);
    // 이 메시지가 현재 열려있는 채팅방의 메시지인지 확인
    if (wsMessage.chatRoomId && wsMessage.chatRoomId !== chatRoom.id) {
      console.log('[디버그] 다른 방의 메시지이므로 UI를 업데이트하지 않습니다.');
      return;
    }

    // CHAT_MESSAGE 타입 처리 (서버에서 온 ChatMessage DTO는 이런 필드들을 가짐)
    if (wsMessage && typeof wsMessage.content === 'string' && wsMessage.senderId) {
      console.log('[디버그] 채팅 메시지 타입 확인. UI 상태 업데이트를 준비합니다.');
      setMessages(prev => {
        console.log(`[디버그] setMessages 실행. 이전 메시지 개수: ${prev.length}`);
        // 이미 메시지 목록에 있는지 확인 (중복 추가 방지)
        if (prev.some(msg => msg.id === wsMessage.id)) {
          console.log(`[디버그] 중복 메시지(ID: ${wsMessage.id})이므로 추가하지 않습니다.`);
          return prev;
        }

        const newMessage: Message = {
          id: wsMessage.id,
          chatRoomId: wsMessage.chatRoomId,
          sender: {
            id: wsMessage.senderId,
            username: wsMessage.senderName,
            displayName: wsMessage.senderName,
            isActive: true,
            createdAt: new Date().toISOString(),
          },
          type: wsMessage.type,
          content: wsMessage.content,
          sequenceNumber: wsMessage.sequenceNumber,
          isEdited: false,
          isDeleted: false,
          createdAt: new Date(wsMessage.timestamp).toISOString(),
        };
        console.log(`[디버그] 새 메시지 추가 완료. 새로운 메시지 개수: ${prev.length + 1}`);
        return [...prev, newMessage];
      });
    } 
    // TYPING_INDICATOR 타입 처리
    else if (wsMessage.type === 'TYPING_INDICATOR') {
      if (wsMessage.userId !== currentUser.id) {
        setIsTyping(wsMessage.isTyping || false);
        if (wsMessage.isTyping) {
          setTimeout(() => setIsTyping(false), 3000);
        }
      }
    } 
    // ERROR 타입 처리
    else if (wsMessage.type === 'ERROR') {
      console.error('WebSocket 에러 메시지:', wsMessage);
      onError((wsMessage as any).message || 'WebSocket 에러가 발생했습니다.');
    }
  }, [lastMessage, chatRoom.id, currentUser.id, onError]);

  // 메시지 목록 로드
  const loadMessages = useCallback(async () => {
    setIsLoadingMessages(true);
    try {
      const response = await messageApi.getMessages(chatRoom.id, 0, 50);
      // 메시지를 시간순으로 정렬 (oldest first)
      const sortedMessages = response.content.sort((a, b) => 
        new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
      );
      setMessages(sortedMessages);
    } catch (error: any) {
      onError(error.response?.data?.message || '메시지를 불러올 수 없습니다.');
    } finally {
      setIsLoadingMessages(false);
    }
  }, [chatRoom.id, currentUser.id, onError]);

  // 메시지 전송
  const handleSendMessage = async (e: React.FormEvent) => {
    e.preventDefault();
    const content = messageInput.trim();
    
    if (!content || !isConnected) {
      return;
    }

    try {
      const wsMessage: WebSocketMessage = {
        type: 'SEND_MESSAGE',
        chatRoomId: chatRoom.id,
        messageType: 'TEXT',
        content,
      };

      sendWebSocketMessage(wsMessage);
      setMessageInput('');

    } catch (error) {
      console.error('📤 메시지 전송 에러:', error);
      onError('메시지 전송에 실패했습니다. 다시 시도해주세요.');
    }
  };

  // 타이핑 인디케이터
  const handleTyping = () => {
    if (!isConnected) return;

    const wsMessage: WebSocketMessage = {
      type: 'TYPING_INDICATOR',
      chatRoomId: chatRoom.id,
      isTyping: true,
    };

    sendWebSocketMessage(wsMessage);

    // 3초 후 타이핑 중단
    if (typingTimeoutRef.current) {
      clearTimeout(typingTimeoutRef.current);
    }
    
    typingTimeoutRef.current = setTimeout(() => {
      const stopTypingMessage: WebSocketMessage = {
        type: 'TYPING_INDICATOR',
        chatRoomId: chatRoom.id,
        isTyping: false,
      };
      sendWebSocketMessage(stopTypingMessage);
    }, 3000);
  };

  // 채팅방 변경 시 메시지 로드
  useEffect(() => {
    loadMessages();
  }, [chatRoom.id, loadMessages]);

  // 새 메시지 시 스크롤
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // WebSocket 에러 처리
  useEffect(() => {
    if (wsError) {
      onError(wsError);
    }
  }, [wsError, onError]);

  // 시간 포맷 유틸리티
  const formatTime = (dateString: string) => {
    const date = new Date(dateString);
    return date.toLocaleTimeString('ko-KR', { 
      hour: '2-digit', 
      minute: '2-digit' 
    });
  };

  return (
    <div className="flex-1 flex flex-col h-screen bg-background">
      {/* 헤더 */}
      <div className="p-4 border-b border-border bg-bg-tertiary flex items-center justify-between">
        <div>
          <h3 className="m-0 text-lg text-text-primary">
            {chatRoom.type === 'GROUP' ? '👥' : '📢'} {chatRoom.name}
          </h3>
          <div className="text-xs text-text-secondary mt-1">
            멤버 {chatRoom.memberCount}명 • ID: {chatRoom.id}
            {chatRoom.description && ` • ${chatRoom.description}`}
          </div>
        </div>
      </div>

      {/* 메시지 영역 */}
      <div className="flex-1 overflow-auto p-4 flex flex-col gap-3">
        {isLoadingMessages ? (
          <div className="text-center p-8 text-text-secondary">
            메시지를 불러오는 중...
          </div>
        ) : messages.length === 0 ? (
          <div className="text-center p-8 text-text-secondary">
            첫 번째 메시지를 보내보세요! 👋
          </div>
        ) : (
          messages.map((message) => {
            const isOwn = message.sender?.id === currentUser.id;
            return (
              <div key={message.id} className="flex flex-col">
                {!isOwn && (
                  <div className="text-xs text-text-secondary mb-1 ml-1">
                    {message.sender?.displayName || message.sender?.username}
                  </div>
                )}
                <div
                  className={
                    isOwn
                      ? "max-w-[70%] px-4 py-3 rounded-2xl self-end bg-my-message text-my-message-text break-words"
                      : "max-w-[70%] px-4 py-3 rounded-2xl self-start bg-other-message text-other-message-text break-words"
                  }
                >
                  <div>{message.content}</div>
                  <div className="text-[11px] opacity-70 mt-1 text-right">
                    {formatTime(message.createdAt)}
                  </div>
                </div>
              </div>
            );
          })
        )}
        
        {/* 타이핑 인디케이터 */}
        {isTyping && (
          <div className="text-xs text-text-secondary italic p-2">
            누군가가 입력 중입니다...
          </div>
        )}
        
        <div ref={messagesEndRef} />
      </div>

      {/* 입력 영역 */}
      <div className="p-4 border-t border-border bg-bg-tertiary">
        <form onSubmit={handleSendMessage}>
          <div className="flex gap-2 items-end">
            <textarea
              value={messageInput}
              onChange={(e) => {
                setMessageInput(e.target.value);
                // handleTyping(); // 타이핑 인디케이터 제거
              }}
              onKeyPress={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  handleSendMessage(e);
                }
              }}
              placeholder={isConnected ? "메시지를 입력하세요..." : "연결 중..."}
              disabled={!isConnected}
              className="flex-1 px-4 py-3 border border-border rounded-3xl text-sm outline-none resize-none min-h-[20px] max-h-[100px]"
              rows={1}
            />
            <button
              type="submit"
              disabled={!isConnected || !messageInput.trim()}
              className={
                isConnected
                  ? "px-5 py-3 bg-primary text-white border-none rounded-3xl cursor-pointer text-sm font-bold hover:bg-primary-hover transition-all duration-200"
                  : "px-5 py-3 bg-gray-300 text-white border-none rounded-3xl cursor-not-allowed text-sm font-bold"
              }
            >
              전송
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
