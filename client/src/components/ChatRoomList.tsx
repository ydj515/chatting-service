import React, { useState, useEffect, useCallback } from 'react';
import { User, ChatRoom, CreateChatRoomRequest } from '../types/index';
import { chatRoomApi } from '../services/api.ts';
import Button from './ui/Button.tsx';
import Input from './ui/Input.tsx';
import { 
  Plus, 
  Search, 
  Users, 
  Hash, 
  MessageCircle, 
  Settings,
  X,
  ChevronRight,
  UserCheck
} from 'lucide-react';

interface ChatRoomListProps {
  currentUser: User;
  selectedChatRoom?: ChatRoom;
  onChatRoomSelect: (chatRoom: ChatRoom) => void;
  onError: (error: string) => void;
}

const ChatRoomList: React.FC<ChatRoomListProps> = ({
  currentUser,
  selectedChatRoom,
  onChatRoomSelect,
  onError,
}) => {
  const [chatRooms, setChatRooms] = useState<ChatRoom[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [createLoading, setCreateLoading] = useState(false);
  const [newRoomData, setNewRoomData] = useState<CreateChatRoomRequest>({
    name: '',
    description: '',
    type: 'GROUP',
    maxMembers: 3,
  });
  const [showJoinModal, setShowJoinModal] = useState(false);
  const [joinRoomId, setJoinRoomId] = useState('');
  const [joinLoading, setJoinLoading] = useState(false);

  const loadChatRooms = useCallback(async () => {
    try {
      setLoading(true);
      const response = await chatRoomApi.getChatRooms();
      setChatRooms(response.content);
    } catch (error: any) {
      console.error('Failed to load chat rooms:', error);
      onError('채팅방 목록을 불러오는데 실패했습니다');
    } finally {
      setLoading(false);
    }
  }, [currentUser.id, onError]);



  // 채팅방 참여
  const handleJoinRoom = async () => {
    if (!joinRoomId.trim()) {
      onError('채팅방 ID를 입력해주세요');
      return;
    }

    try {
      setJoinLoading(true);
      const roomId = parseInt(joinRoomId);
      if (isNaN(roomId)) {
        onError('올바른 채팅방 ID를 입력해주세요');
        return;
      }

      await chatRoomApi.join(roomId);
      await loadChatRooms(); // 채팅방 목록 새로고침
      setShowJoinModal(false);
      setJoinRoomId('');
      
      // 참여한 채팅방 선택
      const joinedRoom = await chatRoomApi.getChatRoom(roomId);
      onChatRoomSelect(joinedRoom);
    } catch (error: any) {
      console.error('Failed to join chat room:', error);
      if (error.response?.status === 404) {
        onError('채팅방을 찾을 수 없습니다');
      } else if (error.response?.status === 409) {
        onError('이미 참여한 채팅방입니다');
      } else if (error.response?.status === 400) {
        onError('채팅방이 가득 찼습니다');
      } else {
        onError('채팅방 참여에 실패했습니다');
      }
    } finally {
      setJoinLoading(false);
    }
  };

  useEffect(() => {
    loadChatRooms();
  }, [loadChatRooms]);

  const handleCreateRoom = async () => {
    if (!newRoomData.name.trim()) {
      onError('채팅방 이름을 입력해주세요');
      return;
    }

    try {
      setCreateLoading(true);
      const newRoom = await chatRoomApi.create(newRoomData);
      setChatRooms(prev => [newRoom, ...prev]);
      setShowCreateModal(false);
      setNewRoomData({
        name: '',
        description: '',
        type: 'GROUP',
        maxMembers: 3,
      });
      onChatRoomSelect(newRoom);
    } catch (error: any) {
      console.error('Failed to create chat room:', error);
      onError('채팅방 생성에 실패했습니다');
    } finally {
      setCreateLoading(false);
    }
  };

  const filteredChatRooms = chatRooms.filter(room =>
    room.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    room.description?.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const getRoomIcon = (type: string) => {
    switch (type) {
      case 'DIRECT':
        return <MessageCircle size={16} />;
      case 'CHANNEL':
        return <Hash size={16} />;
      default:
        return <Users size={16} />;
    }
  };

  const getLastMessageTime = (chatRoom: ChatRoom) => {
    if (!chatRoom.lastMessage?.createdAt) return '';
    
    try {
      const date = new Date(chatRoom.lastMessage.createdAt);
      const now = new Date();
      const diff = now.getTime() - date.getTime();
      const minutes = Math.floor(diff / (1000 * 60));
      const hours = Math.floor(diff / (1000 * 60 * 60));
      const days = Math.floor(diff / (1000 * 60 * 60 * 24));
      
      if (minutes < 1) return '방금';
      if (minutes < 60) return `${minutes}분 전`;
      if (hours < 24) return `${hours}시간 전`;
      if (days < 7) return `${days}일 전`;
      return date.toLocaleDateString('ko-KR', { 
        month: 'short', 
        day: 'numeric' 
      });
    } catch {
      return '';
    }
  };

  return (
    <div className="w-80 h-screen bg-bg-secondary border-r border-border flex flex-col overflow-hidden">
      {/* Header */}
      <div className="p-4 border-b border-border bg-background">
        <div className="flex items-center gap-2 mb-4">
          {/* 아바타 */}
          <div className="w-10 h-10 rounded-full bg-primary flex items-center justify-center text-white font-semibold text-base">
            {currentUser.displayName.charAt(0).toUpperCase()}
          </div>
          <div className="flex-1">
            <div className="font-semibold text-text-primary text-base">{currentUser.displayName}</div>
            <div className="text-sm text-text-secondary">
              <UserCheck size={12} className="inline mr-1" />
              온라인
            </div>
          </div>
          <Button variant="ghost" size="sm">
            <Settings size={16} />
          </Button>
        </div>
      </div>

      {/* Search and Create */}
      <div className="p-4 border-b border-border bg-background">
        <div className="flex gap-1 mb-2">
          <Button
            variant="primary"
            size="md"
            onClick={() => setShowCreateModal(true)}
            className="flex-1"
          >
            <Plus size={16} />
            새 채팅방
          </Button>
          <Button
            variant="secondary"
            size="md"
            onClick={() => setShowJoinModal(true)}
            className="flex-1"
          >
            <UserCheck size={16} />
            참여하기
          </Button>
        </div>
        
        <div className="relative">
          <Search size={16} className="absolute left-4 top-1/2 -translate-y-1/2 text-text-tertiary z-[1]" />
          <Input
            value={searchQuery}
            onChange={setSearchQuery}
            placeholder="채팅방 검색..."
            className="pl-10"
          />
        </div>
      </div>

      {/* Room List */}
      <div className="flex-1 overflow-y-auto p-1">
        {loading ? (
          <div className="p-8 text-center text-text-secondary">
            <div className="animate-pulse-slow">채팅방을 불러오는 중...</div>
          </div>
        ) : filteredChatRooms.length === 0 ? (
          <div className="p-8 text-center text-text-secondary">
            {searchQuery ? '검색 결과가 없습니다' : '채팅방이 없습니다'}
          </div>
        ) : (
          filteredChatRooms.map((room) => {
            const isSelected = selectedChatRoom?.id === room.id;
            return (
              <div
                key={room.id}
                className={`flex items-center p-4 mb-1 rounded-md cursor-pointer transition-all duration-200 border animate-fade-in ${
                  isSelected 
                    ? 'bg-primary-light border-primary' 
                    : 'border-transparent hover:bg-gray-50'
                }`}
                onClick={() => onChatRoomSelect(room)}
              >
                {/* 방 아이콘 */}
                <div className="w-11 h-11 rounded-md bg-gray-200 flex items-center justify-center mr-2 text-text-secondary">
                  {getRoomIcon(room.type)}
                </div>
                
                {/* 방 정보 */}
                <div className="flex-1 min-w-0">
                  <div className="font-semibold text-text-primary text-base mb-1 overflow-hidden text-ellipsis whitespace-nowrap">
                    {room.name}
                  </div>
                  <div className="text-sm text-text-secondary overflow-hidden text-ellipsis whitespace-nowrap">
                    Welcome
                  </div>
                </div>
                
                {/* 메타 정보 */}
                <div className="flex flex-col items-end gap-1">
                  <div className="text-xs text-text-tertiary">
                    {getLastMessageTime(room)}
                  </div>
                  <div className="text-xs text-text-tertiary flex items-center gap-0.5">
                    <Users size={10} />
                    {room.memberCount}
                  </div>
                </div>
                
                <ChevronRight size={16} className="text-text-tertiary" />
              </div>
            );
          })
        )}
      </div>

      {/* Create Room Modal */}
      {showCreateModal && (
        <div
          className="fixed inset-0 bg-black/50 flex items-center justify-center z-[1040]"
          onClick={() => setShowCreateModal(false)}
        >
          <div
            className="bg-background rounded-lg p-8 w-[90%] max-w-[400px] max-h-[80vh] overflow-auto"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between mb-6">
              <h3 className="text-xl font-semibold text-text-primary">새 채팅방 만들기</h3>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setShowCreateModal(false)}
              >
                <X size={16} />
              </Button>
            </div>

            <div className="flex flex-col gap-4">
              <Input
                value={newRoomData.name}
                onChange={(value) => setNewRoomData(prev => ({ ...prev, name: value }))}
                placeholder="채팅방 이름"
                maxLength={100}
                autoFocus
              />

              <div className="flex gap-2 mt-4">
                <Button
                  variant="ghost"
                  size="md"
                  onClick={() => setShowCreateModal(false)}
                  className="flex-1"
                >
                  취소
                </Button>
                <Button
                  variant="primary"
                  size="md"
                  loading={createLoading}
                  onClick={handleCreateRoom}
                  className="flex-1"
                >
                  생성
                </Button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Join Room Modal */}
      {showJoinModal && (
        <div
          className="fixed inset-0 bg-black/50 flex items-center justify-center z-[1040]"
          onClick={() => setShowJoinModal(false)}
        >
          <div
            className="bg-background rounded-lg p-8 w-[90%] max-w-[400px] max-h-[80vh] overflow-auto"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between mb-6">
              <h3 className="text-xl font-semibold text-text-primary">채팅방 참여하기</h3>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setShowJoinModal(false)}
              >
                <X size={16} />
              </Button>
            </div>

            <div className="flex flex-col gap-4">
              <Input
                value={joinRoomId}
                onChange={setJoinRoomId}
                placeholder="채팅방 ID를 입력하세요"
                type="number"
                autoFocus
              />

              <div className="text-sm text-text-secondary p-2 bg-gray-100 rounded-md mt-2">
                채팅방 ID는 채팅방 헤더에서 확인할 수 있습니다.
              </div>

              <div className="flex gap-2 mt-4">
                <Button
                  variant="ghost"
                  size="md"
                  onClick={() => setShowJoinModal(false)}
                  className="flex-1"
                >
                  취소
                </Button>
                <Button
                  variant="primary"
                  size="md"
                  loading={joinLoading}
                  onClick={handleJoinRoom}
                  className="flex-1"
                >
                  참여
                </Button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ChatRoomList;
