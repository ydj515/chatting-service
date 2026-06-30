import React from 'react';

const ServerOfflineView: React.FC = () => {
  return (
    <div className="text-center p-8 bg-bg-secondary rounded-lg shadow-lg max-w-[400px]">
      <div className="text-5xl mb-4">
        🔌
      </div>
      <h2 className="text-text-primary text-xl font-semibold mb-2">
        서버에 연결할 수 없습니다
      </h2>
      <p className="text-text-secondary text-base mb-6 leading-relaxed">
        서버가 실행되지 않았거나 네트워크에 문제가 있습니다.<br />
        서버를 실행한 후 페이지를 새로고침해주세요.
      </p>
      <button
        onClick={() => window.location.reload()}
        className="px-6 py-2 bg-primary text-white border-none rounded-md text-base font-medium cursor-pointer transition-all duration-200 hover:bg-primary-hover"
      >
        새로고침
      </button>
    </div>
  );
};

export default ServerOfflineView;
