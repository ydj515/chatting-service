# 프론트엔드 컨벤션

`chatting-service` 레포의 웹 클라이언트가 따르는 구조, 스타일, 테스트 규칙이다. 현재 프론트엔드는 독립된 Vite React SPA 두 개로 구성되어 있으며, 공통 패키지를 두지 않고 각 앱 안에서 필요한 코드만 유지한다.

## 적용 대상

| 앱 | 역할 | 스택 |
|---|---|---|
| `client` | 사용자 채팅 클라이언트 | Vite, React 18, TypeScript, Tailwind CSS v4 |
| `client-admin` | 운영자 메시지 검색/히스토리/내보내기 클라이언트 | Vite, React 18, TypeScript, Tailwind CSS v4 |

> Kotlin/Spring 백엔드 모듈, 인프라 설정, 배치/검증 스크립트는 이 문서의 직접 대상이 아니다. 다만 프론트엔드가 호출하는 API 계약과 개발 프록시 설정은 함께 고려한다.

## 기본 원칙

1. **앱은 독립 패키지로 유지한다**
   - `client`와 `client-admin`은 각각 `package.json`, `vite.config.ts`, `tsconfig*.json`, `src/`, `public/`을 가진다.
   - 공통 코드 패키지가 없으므로 작은 UI나 유틸은 각 앱 안에 둔다.
   - 두 앱에 같은 개념이 있어도 요구사항이 다르면 무리하게 공유 구조를 만들지 않는다.

2. **`App.tsx`는 얇은 진입점으로 둔다**
   - Vite SPA는 `tool-hub/home`처럼 `App → components/layout/Layout → pages/*` 흐름을 기본 구조로 삼는다.
   - `App.tsx`는 전역 셸과 현재 페이지를 조립하는 수준으로 유지한다.
   - 화면 상태와 주요 핸들러는 `pages/*` 또는 해당 영역 컴포넌트가 소유한다.
   - 페이지 상태가 알림, 로그아웃, 작업 상태처럼 셸 chrome에 주입되어야 하면 `App → pages/* → Layout` 형태를 허용하되, `App.tsx`는 여전히 얇게 유지한다.
   - 특정 영역이 커지면 `components/*`로 분리하고, 네트워크/검증/저장소 로직은 `services/`, `utils/`, `hooks/`로 옮긴다.

3. **도메인 타입과 API 경계를 명확히 한다**
   - 백엔드 응답/요청 타입은 각 앱의 `src/types/index.ts`에 둔다.
   - REST 호출은 `services/*Api.ts`에서 감싼다.
   - URL 조립, 헤더 생성, 응답 정규화처럼 테스트 가능한 로직은 함수로 분리한다.
   - 쿼리 문자열은 문자열 결합보다 `URLSearchParams`와 `encodeURIComponent`를 우선 사용한다.

4. **상태 영속화는 명시적으로 제한한다**
   - 사용자 앱은 세션 복원에 필요한 사용자 정보, 세션 토큰, 만료 시각, 선택 방을 저장한다.
   - 관리자 앱은 보안상 관리자 토큰을 저장하지 않는다.
   - `localStorage` 접근은 Safari 프라이빗 모드나 차단 환경을 고려해 `try/catch`로 보호한다.
   - 저장소 키는 앱별 접두사를 사용한다. 예: `chat_*`, `client_admin_*`, `admin_theme`.
   - 로컬 UI/세션 상태는 Zustand store에 두고, 서버 조회/작업 상태는 TanStack Query로 관리한다.
   - TanStack Query의 `QueryClientProvider`는 `providers/AppProviders.tsx`에서만 설정한다.

5. **실시간/수명주기 로직은 훅과 순수 유틸로 분리한다**
   - WebSocket 연결 관리는 `hooks/useWebSocket.ts`에서 담당한다.
   - 메시지 이벤트, 티켓, 수명주기 계산처럼 DOM 없이 검증 가능한 로직은 `utils/*`에 둔다.
   - 장애 처리, 재시도, 중복 알림 방지 같은 정책은 테스트 가능한 작은 함수로 분리하는 것을 우선한다.

6. **셸과 콘텐츠를 분리한다**
   - 앱 전체 chrome(상단 바, 테마 토글, 알림, 기본 그리드)은 셸 책임으로 본다.
   - 실제 업무 콘텐츠(채팅방 목록, 채팅창, 로그인 폼, 관리자 필터, 상태 패널, 메시지 테이블)는 별도 컴포넌트 책임으로 본다.
   - 단일 화면 SPA라도 `pages/`를 두어 화면 콘텐츠 경계를 명확히 한다.
   - `components/layout/*`는 셸, 헤더, 푸터, 배경처럼 페이지 바깥 chrome만 담당한다.

7. **CSS는 주제별로 분리한다**
   - `src/styles/`에 `theme.css`, `base.css`, `components.css`처럼 토픽 파일을 둔다.
   - 진입 CSS는 `@import "tailwindcss";`와 하위 CSS import만 담는다.
   - CSS `@import`는 최상단에만 올 수 있으므로 일반 규칙을 진입 CSS에 섞지 않는다.
   - import 순서는 캐스케이드 순서다. 기본값은 `theme → base → components` 순으로 둔다.

8. **반복 UI는 타입 있는 React 컴포넌트로 만든다**
   - 재사용 단위는 외워야 하는 전역 CSS 클래스가 아니라 props가 있는 컴포넌트다.
   - 1회용 UI는 억지로 추출하지 않는다.
   - 의미 클래스를 쓰는 반복 UI는 컴포넌트로 감싸 사용 범위를 좁힌다.

9. **`src` 내부 import는 `@/` 절대경로를 사용한다**
   - 두 앱 모두 Vite `resolve.alias`에서 `@`를 `/src`로 매핑한다.
   - TypeScript는 `tsconfig.app.json`의 `baseUrl: "."`와 `paths: { "@/*": ["src/*"] }`로 같은 경로를 해석한다.
   - `src` 안의 TS/TSX 파일은 같은 디렉터리 파일이라도 상대경로나 다른 내부 alias 대신 `@/...`로 import한다.
   - CSS side-effect import도 `@/styles/global.css`처럼 같은 규칙을 따른다.
   - `test:unit`은 `vitest run ...`으로 실행해 Vite/Vitest가 TS와 `@` alias를 같은 방식으로 해석한다.

## 디렉터리 구조

### `client`

```text
client/
  src/
    App.tsx
    index.tsx
    theme.ts
    components/
      AuthGate.tsx
      ChatRoomList.tsx
      ChatWindow.tsx
      ChatWorkspace.tsx
      EmptyChatState.tsx
      LoadingScreen.tsx
      LoginForm.tsx
      ServerOfflineView.tsx
      layout/
        Layout.tsx
      ui/
        Button.tsx
        InfoTooltip.tsx
        Input.tsx
    config/
      appConfig.ts
    hooks/
      useServerHealth.ts
      useTheme.ts
      useWebSocket.ts
    lib/
      queryClient.ts
    pages/
      ChatPage.tsx
    providers/
      AppProviders.tsx
    services/
      api.ts
    stores/
      chatStore.ts
    styles/
      theme.css
      base.css
      components.css
      global.css
    types/
      index.ts
    utils/
      *.ts
      *.test.ts
```

### `client-admin`

```text
client-admin/
  src/
    App.tsx
    index.tsx
    theme.ts
    components/
      AdminControls.tsx
      AdvancedFilters.tsx
      ExportStatus.tsx
      MessageTable.tsx
      RoomStatus.tsx
      layout/
        Layout.tsx
      ui/
        Button.tsx
        Field.tsx
        InfoTooltip.tsx
        Input.tsx
        Select.tsx
    config/
      appConfig.ts
    hooks/
      useTheme.ts
    lib/
      queryClient.ts
    pages/
      AdminPage.tsx
    providers/
      AppProviders.tsx
    services/
      adminApi.ts
      adminApi.test.ts
      adminState.ts
      adminState.test.ts
    stores/
      adminStore.ts
    styles/
      theme.css
      base.css
      components.css
      global.css
    types/
      index.ts
    utils/
      *.test.ts
```

## 컴포넌트 작성 규칙

- 파일명과 컴포넌트명은 `PascalCase`를 사용한다.
- 훅은 `use*` 이름을 사용하고 `hooks/`에 둔다.
- 반복 사용되는 입력, 버튼, 도움말 같은 프리미티브는 `components/ui/`에 둔다.
- 한 화면에서만 쓰이고 의미가 명확한 영역은 과도하게 추상화하지 않는다.
- 아이콘은 `lucide-react`를 우선 사용한다.
- 아이콘만 있는 버튼에는 접근 가능한 이름을 제공한다. 예: `aria-label`, `title`.
- 긴 상태 계산이나 API 호출은 JSX 안에 직접 쓰지 말고 함수로 빼서 읽기 쉽게 유지한다.
- 타입만 필요한 import는 가능하면 `import type`을 사용한다.

## 레이아웃 규칙

### 공통

- `App.tsx`는 `Layout`과 페이지 컴포넌트를 조립하는 얇은 진입점으로 둔다.
- `Layout`은 `components/layout/Layout.tsx`에 둔다.
- 셸 컴포넌트는 화면 chrome과 슬롯 조립을 담당하고, 데이터 조회나 도메인 변환을 직접 수행하지 않는다.
- 상단 바에는 앱 이름, 서버/작업 상태, 테마 토글, 로그아웃처럼 앱 전역 액션만 둔다.
- 페이지 컴포넌트는 화면 상태, 핸들러, 업무 콘텐츠 조립을 담당한다.
- 고정 포맷 영역은 `grid`, `flex`, `minmax`, `min-width: 0`, `overflow`를 명시해 긴 텍스트나 작은 화면에서 깨지지 않게 한다.

### `client`

- 인증 전 화면은 로그인/회원가입 폼 중심의 독립 콘텐츠로 유지한다.
- 인증 후 화면은 다음 세 영역을 기본 구조로 본다.
  - 앱 상단 바: 사용자 정보, 서버 상태, 테마 토글, 로그아웃
  - 방 목록 영역: 방 검색/생성/참여, 방 선택
  - 채팅 영역: 메시지 히스토리, 실시간 메시지, 입력창
- 알림 토스트는 화면 chrome에 속하므로 채팅창 내부에 넣지 않는다.
- WebSocket 연결 상태와 메시지 병합 정책은 레이아웃 컴포넌트가 아니라 훅/유틸에서 처리한다.

### `client-admin`

- 관리자 화면은 운영 도구이므로 밀도 있는 단일 작업 화면을 유지한다.
- 기본 구조는 상단 바 → 컨트롤 영역 → 결과 패널 순서로 본다. 셸(`Layout`)은 헤더와 콘텐츠 그리드를, 컨트롤과 패널은 페이지 콘텐츠를 담당한다.
- 필터/토큰/작업 버튼은 컨트롤 영역에 묶고, 결과 표와 방 상태는 별도 패널로 분리한다.
- 표 영역은 가로 스크롤과 행 높이를 안정적으로 유지해 운영자가 반복 조회할 때 레이아웃이 흔들리지 않게 한다.
- 관리자 토큰 입력은 전역 상태로는 들고 있어도 영속 저장하지 않는다.

## 스타일 규칙

1. **진입 CSS는 import 전용으로 둔다**
   - 두 앱 모두 `src/index.tsx`에서 `@/styles/global.css`를 import한다.
   - `global.css`는 `@import "tailwindcss";`와 하위 CSS import만 담는다.
   - import 순서는 `theme.css → base.css → components.css`를 기본으로 한다.
   - 의미 클래스는 위치 계산이 복잡한 위젯(예: 툴팁)에만 제한적으로 두며, 커지면 `components.css`를 `tooltip.css`처럼 더 나눌 수 있다.

2. **디자인 값은 토큰으로 관리하고 두 앱이 같은 토큰 네이밍을 공유한다**
   - 공통 패키지는 두지 않되, 두 앱의 `theme.css`는 같은 `--color-*`, `--radius-*`, `--shadow-*`, `--font-*` 네이밍 체계를 사용한다. 같은 값을 다른 이름으로 중복 정의하지 않는다.
   - Tailwind 유틸리티로 쓸 값은 `@theme`에 둔다.
   - 런타임 테마에서 바뀌는 값은 `[data-theme="dark"]`에서 같은 토큰을 재정의한다. `dark:` 변형 대신 토큰 재정의로 라이트/다크를 처리해 `bg-background` 같은 유틸리티 한 줄이 두 테마를 모두 커버하게 한다.
   - 새 색상, 반경, 그림자, 폰트 값은 먼저 토큰으로 둘 수 있는지 확인하고, 한쪽 앱에만 필요한 값이라도 네이밍 규칙을 맞춘다.

3. **인라인 스타일을 쓰지 않는다**
   - `style={...}`, `<style>`, `React.CSSProperties`, 직접 DOM style 조작은 금지한다.
   - 이 규칙은 `sourceHygiene.test.ts`에서 검사한다.

4. **로컬 폰트만 사용한다**
   - 폰트는 각 앱의 `public/fonts/chat-app-sans-variable.woff2`를 사용한다.
   - 외부 폰트 CDN, jsDelivr 등 원격 디자인 리소스는 사용하지 않는다.

5. **두 앱 모두 Tailwind 유틸리티 + `@theme` 토큰을 기본 전략으로 쓴다**
   - `client`와 `client-admin`은 같은 디자인 시스템을 공유한다. 레이아웃·간격·색상은 Tailwind 유틸리티로 작성하고, 값은 `@theme` 토큰에서 가져온다.
   - 표, 패널, 필터 폼 같은 운영 UI도 전역 의미 클래스 대신 유틸리티로 작성한다.
   - 폼 프리미티브(버튼, 입력, 셀렉트, 필드)는 전역 클래스가 아니라 `components/ui/*`의 타입 있는 컴포넌트로 표준화한다.
   - 의미 클래스(CSS 클래스)는 위치 계산이 복잡해 유틸리티로 표현하기 번거로운 위젯에만 제한적으로 쓴다. 현재는 `info-tooltip-*`이 유일한 예다.
   - 의미 클래스를 부득이 추가할 때는 전역 이름 충돌을 피하도록 컴포넌트/영역 접두사를 붙인다. 예: `info-tooltip-*`.

## 테마 규칙

- 테마 전환 방식은 `document.documentElement`의 `data-theme` 속성을 기준으로 한다.
- 값은 `light` 또는 `dark`만 사용한다. 예: `<html data-theme="dark">`.
- 기존 `.dark` 클래스 기반 코드는 `[data-theme="dark"]` 기준으로 교체한다.
- `src/theme.ts`는 초기 테마 결정과 테마 값 검증 같은 순수 함수를 둔다.
- `src/hooks/useTheme.ts`는 테마 상태, `data-theme` 동기화 effect, 토글 함수를 제공한다.
- Tailwind `dark:` 변형이 필요한 경우 앱 CSS에 다음 커스텀 variant를 둔다.

  ```css
  @custom-variant dark (&:where([data-theme="dark"], [data-theme="dark"] *));
  ```

- 앱 렌더 전 테마 플래시를 줄이기 위해 각 앱의 `index.html`에서 `data-theme`를 선적용한다.

  ```html
  <script>
    (function () {
      try {
        // client-admin에서는 'admin_theme'을 사용한다.
        var key = 'chat_theme';
        var t = localStorage.getItem(key);
        if (t !== 'light' && t !== 'dark') {
          t = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
        }
        document.documentElement.setAttribute('data-theme', t);
      } catch (e) {}
    })();
  </script>
  ```

- 사용자가 직접 선택한 테마는 앱별 키에 저장한다.
  - `client`: `chat_theme`
  - `client-admin`: `admin_theme`
- 저장된 테마가 없으면 `prefers-color-scheme`을 따른다.
- OS 테마 변경은 사용자가 직접 테마를 저장하지 않았을 때만 반영한다.

## API와 설정 규칙

- API base URL, timeout, WebSocket URL 같은 값은 `src/config/appConfig.ts`에 모은다.
- Vite 개발 서버는 `/api`를 백엔드로 프록시한다.
- 프록시 대상은 `VITE_DEV_PROXY_TARGET`으로 바꿀 수 있다.
- `client-admin`은 `client`와 동시에 실행할 수 있도록 개발 서버 포트를 `5174`로 고정한다.
- 관리자 요청에는 `X-Admin-Token` 헤더를 사용하며, 토큰은 저장하지 않는다.
- 인증 세션이나 WebSocket 티켓처럼 만료가 있는 값은 만료 시각을 함께 검증한다.
- 화면 데이터 조회나 폼/버튼에서 시작되는 서버 작업은 TanStack Query의 `useQuery`/`useMutation`으로 감싼다.
- 컴포넌트 사이에서 공유되는 로컬 상태는 `stores/*Store.ts`의 Zustand store에 둔다.
- Zustand store는 서버 데이터를 직접 fetch하지 않는다. API 호출은 `services/*`와 TanStack Query hook/mutation 경계에서 수행한다.
- WebSocket 티켓 발급처럼 연결 수명주기에 강하게 묶인 단발 호출은 해당 훅 내부에서 관리할 수 있다.
- `QueryClient` 기본 옵션은 각 앱의 `lib/queryClient.ts`에 둔다.

## 테스트와 검증

각 앱에서 변경한 범위에 맞게 아래 명령을 실행한다.

```bash
cd client
npm run test:unit
npm run build
```

```bash
cd client-admin
npm run test:unit
npm run build
```

- `test:unit`은 `vitest run`으로 실행한다. Vitest는 Vite 설정과 같은 `@` alias를 사용하므로 테스트와 앱 빌드의 모듈 해석이 일치한다.
- `build`는 `tsc -b`와 `vite build`를 함께 수행하므로 타입 오류와 번들 오류를 같이 잡는다.
- 프론트엔드 공통 규칙을 건드렸다면 두 앱 모두 검증한다.
- 스타일 변경은 가능하면 브라우저에서 라이트/다크 테마를 모두 확인한다.
- UI 테스트가 없는 영역은 빌드 산출 CSS에 주요 클래스가 보존됐는지 확인한다.
- 구조만 바꾸는 리팩터링은 `className`, 표시 텍스트, API 요청 형태를 보존한다.

## 새 코드 추가 체크리스트

1. 새 타입이 필요하면 `src/types/index.ts`에 계약을 먼저 정의한다.
2. API 호출은 `services/`에 두고 URL/헤더 조립은 테스트 가능한 함수로 분리한다.
3. 화면 전용 상태는 `pages/*` 또는 해당 컴포넌트에 두고, 재사용되는 상태 수명주기는 훅으로 뺀다.
4. 반복 UI만 `components/ui/`로 추출한다.
5. 새 스타일 값은 토큰 또는 CSS 변수로 표현한다.
6. `theme.ts` + `useTheme.ts`를 사용하고 다크모드는 `data-theme`로 통일한다.
7. 인라인 스타일과 외부 폰트 CDN을 추가하지 않는다.
8. 변경한 앱의 `npm run test:unit`과 `npm run build`를 실행한다.

## 리팩터링 기준

- 구조만 바꾸는 리팩터링은 렌더 결과, 텍스트, API 요청 형태를 보존한다.
- 큰 CSS 블록을 옮길 때는 선택자 누락이나 캐스케이드 순서 변경을 특히 조심한다.
- 구조 리팩터링은 `App → Layout → pages/*`를 우선 목표로 한다.
- `pages/*`가 비대해질 때는 먼저 다음 경계를 기준으로 나눈다.
  - 상단 바/알림/필터/테이블/채팅창 같은 화면 영역
  - API 호출과 URL 조립
  - storage 입출력
  - WebSocket 이벤트 해석
- 공통 패키지를 만들기 전에는 실제 중복이 충분히 반복되는지 확인한다. 현재 레포에서는 앱별 복제가 더 단순하다.
