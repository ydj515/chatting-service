import React, { useState, useEffect, useRef, useCallback } from 'react';
import { ChatRoom, Message, User, WebSocketMessage } from '../types/index';
import { messageApi } from '../services/api.ts';
import { useWebSocket } from '../hooks/useWebSocket.ts';
import {
  applyWebSocketMessageEvent,
  boundedLiveFeedMessages,
  createClientMessageId,
  messageRenderKey,
} from '../utils/messageEvents.ts';
import { Copy, Check } from 'lucide-react';

interface ChatWindowProps {
  currentUser: User;
  sessionToken: string;
  chatRoom: ChatRoom;
  onError: (error: string) => void;
  onSuccess?: (message: string) => void;
}

export const ChatWindow: React.FC<ChatWindowProps> = ({
  currentUser,
  sessionToken,
  chatRoom,
  onError,
  onSuccess,
}) => {
  const [messages, setMessages] = useState<Message[]>([]);
  const [messageInput, setMessageInput] = useState('');
  const [isLoadingMessages, setIsLoadingMessages] = useState(false);
  const [isTyping, setIsTyping] = useState(false);
  const [copied, setCopied] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const typingTimeoutRef = useRef<number>();
  const copyResetRef = useRef<number>();

  // 채팅방 ID 복사 — 클립보드에 쓰고 토스트 + 아이콘을 잠시 체크로 전환
  const handleCopyRoomId = async () => {
    const id = String(chatRoom.id);
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(id);
      } else {
        // 구형 브라우저 폴백
        const textarea = document.createElement('textarea');
        textarea.value = id;
        textarea.className = 'clipboard-fallback-textarea';
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        textarea.remove();
      }
      setCopied(true);
      onSuccess?.('채팅방 ID를 복사했어요');
      if (copyResetRef.current) {
        clearTimeout(copyResetRef.current);
      }
      copyResetRef.current = window.setTimeout(() => setCopied(false), 1500);
    } catch {
      onError('복사에 실패했어요. ID를 직접 선택해 복사해주세요.');
    }
  };

  // 언마운트 시 복사 타이머 정리
  useEffect(() => {
    return () => {
      if (copyResetRef.current) {
        clearTimeout(copyResetRef.current);
      }
    };
  }, []);

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
    if (!lastMessage) return;

    if (lastMessage.type === 'TYPING_INDICATOR') {
      if (lastMessage.userId !== currentUser.id) {
        setIsTyping(lastMessage.isTyping || false);
        if (lastMessage.isTyping) {
          setTimeout(() => setIsTyping(false), 3000);
        }
      }
      return;
    }

    if (lastMessage.type === 'ERROR') {
      onError(lastMessage.message || 'WebSocket 에러가 발생했습니다.');
      return;
    }

    setMessages((prev) => applyWebSocketMessageEvent(prev, lastMessage, chatRoom.id));
  }, [lastMessage, chatRoom.id, currentUser.id, onError]);

  // 메시지 목록 로드
  const loadMessages = useCallback(async () => {
    setIsLoadingMessages(true);
    try {
      const response = await messageApi.getMessages(chatRoom.id, 0, 50);
      setMessages(boundedLiveFeedMessages(response.content));
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
        clientMessageId: createClientMessageId(),
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
          <h3 className="m-0 text-lg font-semibold text-text-primary">
            {chatRoom.name}
          </h3>
          <div className="text-xs text-text-secondary mt-1 flex items-center gap-1 flex-wrap">
            <span>멤버 {chatRoom.memberCount}명</span>
            <span className="text-text-tertiary">•</span>
            <span className="inline-flex items-center gap-1">
              ID: {chatRoom.id}
              <button
                type="button"
                onClick={handleCopyRoomId}
                className="inline-flex items-center justify-center p-0.5 rounded-sm cursor-pointer bg-transparent border-none text-text-tertiary transition-colors duration-150 hover:text-primary hover:bg-primary-light"
                aria-label="채팅방 ID 복사"
                title={copied ? '복사됨' : 'ID 복사'}
              >
                {copied ? <Check size={13} className="text-success" /> : <Copy size={13} />}
              </button>
            </span>
            {chatRoom.description && (
              <>
                <span className="text-text-tertiary">•</span>
                <span>{chatRoom.description}</span>
              </>
            )}
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
              <div key={messageRenderKey(message)} className="flex flex-col">
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
                  ? "px-5 py-3 bg-primary text-white border-none rounded-3xl cursor-pointer text-sm font-semibold hover:bg-primary-hover transition-all duration-200"
                  : "px-5 py-3 bg-gray-100 text-gray-400 border-none rounded-3xl cursor-not-allowed text-sm font-semibold"
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
