export type AuthMode = 'login' | 'register';
export type AuthField = 'username' | 'password' | 'displayName';

export interface AuthFormData {
  username: string;
  password: string;
  displayName: string;
}

export interface AuthValidationResult {
  isValid: boolean;
  message?: string;
}

export const AUTH_FIELD_LIMITS = {
  username: {
    min: 3,
    max: 20,
  },
  password: {
    min: 3,
    max: 255,
  },
  displayName: {
    min: 1,
    max: 50,
  },
} as const;

const DEFAULT_BAD_REQUEST_MESSAGE =
  '입력값을 확인해주세요. 사용자명은 3-20자, 비밀번호는 최소 3자, 표시명은 1-50자입니다.';

export const getAuthFieldPlaceholder = (field: AuthField): string => {
  switch (field) {
    case 'username':
      return '사용자명 (3-20자)';
    case 'displayName':
      return '표시명 (1-50자)';
    case 'password':
      return '비밀번호 (최소 3자)';
  }
};

export const validateAuthForm = (
  formData: AuthFormData,
  mode: AuthMode,
): AuthValidationResult => {
  const username = formData.username.trim();
  const password = formData.password;
  const displayName = formData.displayName.trim();

  if (!username) {
    return { isValid: false, message: '사용자명을 입력해주세요' };
  }

  if (mode === 'register') {
    const { min, max } = AUTH_FIELD_LIMITS.username;
    if (username.length < min || username.length > max) {
      return { isValid: false, message: '사용자명은 3-20자 사이여야 합니다' };
    }
  }

  if (!password.trim()) {
    return { isValid: false, message: '비밀번호를 입력해주세요' };
  }

  if (mode === 'register' && password.length < AUTH_FIELD_LIMITS.password.min) {
    return { isValid: false, message: '비밀번호는 최소 3자 이상이어야 합니다' };
  }

  if (mode === 'register') {
    if (!displayName) {
      return { isValid: false, message: '표시명을 입력해주세요' };
    }

    const { min, max } = AUTH_FIELD_LIMITS.displayName;
    if (displayName.length < min || displayName.length > max) {
      return { isValid: false, message: '표시 이름은 1-50자 사이여야 합니다' };
    }
  }

  return { isValid: true };
};

export const getAuthErrorMessage = (error: any): string => {
  const response = error?.response;
  const data = response?.data;

  if (typeof data === 'string' && data.trim()) {
    return data;
  }

  if (Array.isArray(data?.errors) && data.errors.length > 0) {
    const messages = data.errors
      .map((item: any) => item?.message ?? item?.defaultMessage ?? item)
      .filter((message: any) => typeof message === 'string' && message.trim());

    if (messages.length > 0) {
      return messages.join('\n');
    }
  }

  if (typeof data?.message === 'string' && data.message.trim()) {
    return data.message;
  }

  if (response?.status === 400) {
    return DEFAULT_BAD_REQUEST_MESSAGE;
  }

  return error?.message || '인증에 실패했습니다';
};
