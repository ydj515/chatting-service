import React from 'react';
import type { LoginResponse } from '@/types/index.ts';
import LoginForm from '@/components/LoginForm.tsx';
import ServerOfflineView from '@/components/ServerOfflineView.tsx';

interface AuthGateProps {
  serverStatus: 'checking' | 'online' | 'offline';
  onLogin: (response: LoginResponse) => void;
  onError: (message: string) => void;
}

const AuthGate: React.FC<AuthGateProps> = ({ serverStatus, onLogin, onError }) => {
  return (
    <div className="flex justify-center items-center h-screen w-screen bg-bg-secondary p-6">
      {serverStatus === 'offline' ? (
        <ServerOfflineView />
      ) : (
        <LoginForm
          onLogin={onLogin}
          onError={onError}
        />
      )}
    </div>
  );
};

export default AuthGate;
