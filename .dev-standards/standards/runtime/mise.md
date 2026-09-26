# mise Runtime Guidelines

이 문서는 프로젝트의 런타임과 검증 명령을 `mise.toml`로 재현하기 위한 공통
계약입니다. 중앙 표준은 task의 의미를 정의하고, 정확한 버전과 명령은 각 프로젝트가 소유합니다.

## 버전과 설정 소유권

- 언어 런타임과 package manager 버전은 프로젝트 루트의 `mise.toml`에 고정합니다.
- `min_version`에는 이 설정과 task가 요구하는 최소 mise 버전을 명시합니다. mise 실행 파일
  자체는 `mise.lock` 대상이 아니므로 CI action 또는 설치 경로의 버전도 별도로 고정합니다.
- CI와 로컬 개발자는 같은 `mise.toml`을 사용하고 별도 CI 전용 버전을 만들지 않습니다.
- `mise.local.toml`에는 개인 설정만 두고 저장소의 `.gitignore`에 포함합니다.
- 시크릿은 `mise.toml`에 기록하지 않고 CI secret 또는 승인된 secret manager에서 주입합니다.

## Lockfile과 신뢰 경계

- `[settings] lockfile = true`를 활성화하고 `mise.toml`과 생성된 `mise.lock`을 함께 버전
  관리합니다. `mise.lock`에는 concrete version과 backend가 지원하는 URL, checksum 및
  verification metadata가 기록됩니다.
- tool 버전을 변경할 때는 `mise lock` 또는 대상 tool만 지정한 갱신 명령을 사용하고
  lockfile diff에서 version, backend, URL과 checksum 변화를 검토합니다.
- `mise.lock`은 application dependency까지 잠그지 않습니다. `pnpm-lock.yaml`, `uv.lock`,
  Gradle lock state 같은 생태계별 lockfile을 별도로 유지합니다.
- `mise.local.toml`을 사용하는 경우 생성되는 `mise.local.lock`도 함께 ignore해 개인
  runtime 선택이 공유 lockfile에 섞이지 않게 합니다.
- 처음 받은 저장소에서는 `mise.toml`, task, hook과 lockfile을 먼저 검토합니다. 상위 경로나
  모든 하위 설정을 일괄 신뢰하지 않고 필요한 project config만 신뢰합니다.

## 공통 task 계약

- `mise run lint`: formatter check와 정적 분석을 실행합니다.
- `mise run test`: 프로젝트의 자동화된 테스트를 실행합니다.
- `mise run verify`: 커밋 전 요구되는 전체 로컬 품질 게이트를 실행합니다.
- 추가 task가 필요하면 `verify`에서 호출하거나 README에 별도 실행 조건을 명시합니다.

언어 또는 build tool에 맞는 시작점을 선택합니다.

- Gradle: `templates/mise/gradle/mise.toml.example`
- Go: `templates/mise/go/mise.toml.example`
- Maven: `templates/mise/maven/mise.toml.example`
- Python: `templates/mise/python/mise.toml.example`
- TypeScript: `templates/mise/typescript/mise.toml.example`

예제는 task 이름과 실행 진입점의 기준이며, 실제 plugin과 package script가 전체
`verify` 경로에 연결되어 있는지는 각 프로젝트가 검증합니다. Maven의 `lint` 예시는
테스트를 생략하므로 전체 성공 판단에는 반드시 `mise run verify`를 사용합니다. Go의
race test는 비용을 고려해 별도 task로 제공하며 필요한 CI 경로에서 명시적으로 실행합니다.
Python 예제는 uv lockfile을 기준으로 환경을 동기화하고 Ruff와 공식 npm Pyright CLI를
각각 고정합니다. TypeScript 예제는 pnpm 버전을 `package.json#packageManager`와 맞추고,
frozen lockfile 설치 후 package script가 Prettier 또는 Biome 중 선택한 formatter를
실행하게 합니다.

`verify`가 `lint`와 `test`에 의존하도록 구성하면 로컬과 CI가 같은 진입점을 사용할 수 있습니다.

```toml
[tasks.verify]
depends = ["lint", "test"]
```

## 사용 흐름

```sh
mise trust --show
mise lock
mise install
mise current
mise tasks ls
mise run verify
```

- `mise trust --show`는 상태만 확인하며 설정을 신뢰 처리하지 않습니다.
- `mise install` 후 lockfile과 실제 선택된 버전을 확인합니다. lockfile을 준비한 CI는
  `MISE_LOCKED=1 mise install`로 누락된 resolution이 조용히 추가되지 않게 합니다.
- task 이름만 존재하는지 확인하는 데 그치지 않고 `mise run verify`의 종료 상태를 확인합니다.
- 일부 task만 실행했다면 전체 검증을 통과한 것으로 보고하지 않습니다.

## 변경 기준

- 런타임 major 버전 변경은 compiler, plugin, dependency peer range와 CI runner 호환성을 함께 확인합니다.
- lockfile을 사용하는 프로젝트는 런타임 변경과 lockfile 재생성을 같은 변경에서 검증합니다.
- 여러 저장소에 같은 버전을 적용하더라도 각 저장소의 실제 빌드와 테스트 결과를 별도로 확인합니다.

참고: [mise configuration](https://mise.jdx.dev/configuration.html),
[mise.lock](https://mise.jdx.dev/dev-tools/mise-lock.html),
[mise trust](https://mise.jdx.dev/cli/trust.html)
