# Worktree 작업 가이드

병렬 작업, 기존 변경과의 격리, worktree 생성·재사용·정리 또는 로컬 설정 복사를 다룰 때 읽습니다.

## 선택과 생성

- 프로젝트의 명시적 작업 위치를 우선하고, 이미 해당 작업을 위한 worktree에 있다면
  재사용합니다. 조회만 하거나 깨끗한 작업 브랜치에서 단일 작업을 수행할 때는 새
  worktree를 불필요하게 만들지 않습니다.
- 다른 작업의 미커밋 변경을 보존해야 하거나 작업을 병렬 진행할 때 별도 worktree를
  사용합니다. 먼저 [branch 가이드](branch.md)로 기준 ref와 작업 브랜치를 확인합니다.
- `git worktree list`로 사용 중인 경로·브랜치를 확인합니다. 경로가 이미 존재하면
  덮어쓰지 않습니다. 프로젝트 밖 형제 경로나 agent의 관리 경로를 사용하고, 저장소 내부
  경로가 필요하면 해당 디렉터리의 ignore 여부를 먼저 확인합니다.

```sh
git worktree add -b <task-branch> <new-worktree-path> <verified-base-ref>
```

- 일반 Git worktree 생성은 기존 미커밋 변경을 복사하지 않습니다. 필요한 변경의 이전은
  사용자가 의도한 범위와 방법을 확인하며, 원본 작업 디렉터리를 임의로 정리하지 않습니다.
- 생성 후 해당 디렉터리에서 `git status --short`, `git branch --show-current`를 확인하고
  프로젝트 setup·검증 명령을 실행합니다. detached HEAD를 사용하는 관리 도구도 있으므로
  브랜치 이름이 비었다는 이유만으로 기존 작업을 초기화하지 않습니다.
- 각 작업의 명령과 파일 수정은 해당 worktree 경로에서 실행합니다. worktree는 DB, 포트,
  외부 서비스까지 분리하지 않으므로 실행 환경의 충돌도 확인합니다.

## 루트 `.worktreeinclude`

- 프로젝트 루트에 파일을 두고 기존 항목과 주석을 보존합니다. 필요한 파일이 없으면
  주석만 있는 파일로 유지합니다. 기본 템플릿은 복사할 경로를 지정하지 않습니다.
- 필요한 로컬 설정만 프로젝트 상대 경로로 명시하고 `git check-ignore -- <path>`로
  ignore 여부를 확인합니다. 추적 파일은 checkout으로 제공되므로 나열하지 않습니다.
- 광범위한 glob, 의존성 캐시, 빌드 산출물, `.git/`를 추가하지 않습니다. 환경 파일은
  프로젝트에서 복사가 필요한 것으로 확인된 경우에만 경로를 추가하며 내용을 출력하지 않습니다.
- 지원 client가 생성하는 worktree에서는 Git-ignored 파일 중 패턴에 맞는 파일을 복사할
  수 있습니다. 일반 `git worktree add`는 이 파일을 자동 처리하지 않습니다.
- Codex는 로컬 관리 worktree, Claude Code는 지원되는 Git worktree 생성 경로에서 이
  기능을 제공합니다. 원격 환경, Gemini, 사용자 정의 hook 등은 지원을 가정하지 말고
  생성 후 필요한 파일의 존재를 확인합니다.

## 완료와 정리

- 작업한 경로, 브랜치 또는 commit, 검증 결과와 남은 변경을 보고합니다.
- worktree·브랜치 삭제는 명시적인 사용자 요청이 있을 때만 수행합니다. 먼저 미커밋 및
  미추적 파일, 미병합 commit, 로컬 설정 보존 여부를 확인합니다. 강제 삭제를 기본으로
  사용하지 않습니다. 남은 변경이 있으면 정리 전에 보존 방법을 확인합니다.

공식 참고: [Git worktree](https://git-scm.com/docs/git-worktree),
[Codex worktrees](https://learn.chatgpt.com/docs/environments/git-worktrees),
[Claude Code worktrees](https://code.claude.com/docs/en/worktrees).
