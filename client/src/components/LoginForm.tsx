import React, { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { LoginRequest, LoginResponse, RegisterRequest } from '@/types/index.ts';
import { userApi } from '@/services/api.ts';
import {
  AUTH_FIELD_LIMITS,
  getAuthErrorMessage,
  getAuthFieldPlaceholder,
  validateAuthForm,
} from '@/utils/authValidation.ts';
import Button from '@/components/ui/Button.tsx';
import Input from '@/components/ui/Input.tsx';
import { MessageCircle, UserPlus, LogIn, Lock, User as UserIcon } from 'lucide-react';

interface LoginFormProps {
  onLogin: (response: LoginResponse) => void;
  onError: (error: string) => void;
}

const LoginForm: React.FC<LoginFormProps> = ({ onLogin, onError }) => {
  const [isLogin, setIsLogin] = useState(true);
  const [formData, setFormData] = useState({
    username: '',
    password: '',
    displayName: '',
  });

  const authMutation = useMutation({
    mutationFn: async () => {
      if (isLogin) {
        const loginData: LoginRequest = {
          username: formData.username.trim(),
          password: formData.password,
        };
        return userApi.login(loginData);
      }

      const registerData: RegisterRequest = {
        username: formData.username.trim(),
        password: formData.password,
        displayName: formData.displayName.trim(),
      };

      await userApi.register(registerData);
      return userApi.login({
        username: registerData.username,
        password: registerData.password,
      });
    },
    onSuccess: onLogin,
    onError: (error) => {
      console.error('Authentication error:', error);
      onError(getAuthErrorMessage(error));
    },
  });

  const handleInputChange = (field: string, value: string) => {
    setFormData(prev => ({
      ...prev,
      [field]: value
    }));
  };

  const validateForm = () => {
    const result = validateAuthForm(formData, isLogin ? 'login' : 'register');
    if (!result.isValid) {
      onError(result.message || '입력값을 확인해주세요');
      return false;
    }

    return true;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    if (!validateForm()) return;
    authMutation.mutate();
  };

  const toggleMode = () => {
    setIsLogin(!isLogin);
    setFormData({
      username: '',
      password: '',
      displayName: '',
    });
  };

  return (
    <div className="w-full max-w-[400px] p-8 bg-background rounded-xl shadow-xl border border-border-light animate-fade-in">
      {/* 헤더 영역 */}
      <div className="text-center mb-8">
        {/* 로고 아이콘 */}
        <div className="w-16 h-16 bg-primary rounded-full flex items-center justify-center mx-auto mb-4">
          <MessageCircle size={32} color="white" />
        </div>
        <h1 className="text-2xl font-bold text-text-primary mb-1">
          {isLogin ? '로그인' : '회원가입'}
        </h1>
        <p className="text-base text-text-secondary m-0">
          {isLogin 
            ? '채팅을 시작하려면 로그인하세요' 
            : '새 계정을 만들어 채팅을 시작하세요'
          }
        </p>
      </div>

      {/* 입력 폼 */}
      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        {/* 사용자명 입력 */}
        <div className="relative">
          <UserIcon size={16} className="absolute left-4 top-1/2 -translate-y-1/2 text-text-tertiary z-[1]" />
          <Input
            type="text"
            value={formData.username}
            onChange={(value) => handleInputChange('username', value)}
            placeholder={getAuthFieldPlaceholder('username')}
            className="pl-10"
            autoFocus
            maxLength={AUTH_FIELD_LIMITS.username.max}
          />
        </div>

        {/* 회원가입 시 표시명 입력 */}
        {!isLogin && (
          <>
            <div className="relative">
              <UserIcon size={16} className="absolute left-4 top-1/2 -translate-y-1/2 text-text-tertiary z-[1]" />
              <Input
                type="text"
                value={formData.displayName}
                onChange={(value) => handleInputChange('displayName', value)}
                placeholder={getAuthFieldPlaceholder('displayName')}
                className="pl-10"
                maxLength={AUTH_FIELD_LIMITS.displayName.max}
              />
            </div>
          </>
        )}

        {/* 비밀번호 입력 */}
        <div className="relative">
          <Lock size={16} className="absolute left-4 top-1/2 -translate-y-1/2 text-text-tertiary z-[1]" />
          <Input
            type="password"
            value={formData.password}
            onChange={(value) => handleInputChange('password', value)}
            placeholder={getAuthFieldPlaceholder('password')}
            className="pl-10"
            maxLength={AUTH_FIELD_LIMITS.password.max}
          />
        </div>

        {/* 제출 버튼 */}
        <Button
          type="submit"
          variant="primary"
          size="lg"
          loading={authMutation.isPending}
          className="mt-4"
        >
          {isLogin ? (
            <>
              <LogIn size={18} />
              로그인
            </>
          ) : (
            <>
              <UserPlus size={18} />
              회원가입
            </>
          )}
        </Button>
      </form>

      {/* 모드 전환 (로그인/회원가입) */}
      <div className="text-center mt-6">
        <span className="text-sm text-text-secondary">
          {isLogin ? '계정이 없으신가요? ' : '이미 계정이 있으신가요? '}
        </span>
        <button
          type="button"
          onClick={toggleMode}
          className="text-primary no-underline font-medium cursor-pointer text-sm bg-transparent border-none"
        >
          {isLogin ? '회원가입' : '로그인'}
        </button>
      </div>
    </div>
  );
};

export default LoginForm;
