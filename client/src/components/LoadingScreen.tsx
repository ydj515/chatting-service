import React from 'react';

const LoadingScreen: React.FC = () => {
  return (
    <div className="flex justify-center items-center h-screen bg-background font-sans">
      <div className="text-center p-8">
        <div className="text-5xl mb-6 animate-pulse-slow">
          💬
        </div>
        <h2 className="text-text-primary text-xl font-semibold mb-2">
          채팅 앱 로딩 중...
        </h2>
        <p className="text-text-secondary text-base">
          잠시만 기다려주세요
        </p>
      </div>
    </div>
  );
};

export default LoadingScreen;
