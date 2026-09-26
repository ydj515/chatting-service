# Branch 작업 가이드

새 변경 작업을 시작하거나 브랜치를 생성·전환하기 전에 읽습니다.

## 작업 시작

- `git status --short`, `git branch --show-current`, `git worktree list`로 기존 변경,
  현재 브랜치와 작업 디렉터리를 확인합니다. 기존 변경을 자동 stash하거나 버리지 않습니다.
- 사용자가 지정한 브랜치와 저장소의 명명 규칙을 우선합니다. 별도 규칙이 없으면
  `feat/<topic>`, `fix/<topic>`, `docs/<topic>`, `chore/<topic>`처럼 목적을 표시합니다.
- 작업 하나에 작업 브랜치 하나를 사용합니다. 기본·보호 브랜치에서 새 변경을 직접
  시작하지 않습니다. 이미 해당 작업 브랜치에 있다면 그대로 이어갑니다.
- 기준 브랜치는 프로젝트 지침, PR 대상 또는 원격 기본 브랜치 정보로 확인합니다.
  `main`이나 현재 `HEAD`가 올바른 기준이라고 임의로 가정하지 않습니다.
- 다른 작업의 미커밋 변경이 있거나 병렬 작업이 필요하면
  [worktree 가이드](worktree.md)를 따라 분리합니다. 같은 브랜치를 두 worktree에
  강제로 체크아웃하지 않습니다.
- 도구가 제공한 detached HEAD worktree에서는 작업을 유지할 수 있습니다. 브랜치가
  필요해지면 현재 작업 커밋에서 생성하며, 잘못된 기준으로 재생성해 변경을 잃지 않습니다.

## 변경과 완료

- 수정·검증·commit은 선택한 작업 디렉터리에서 실행합니다. 작업 범위의 diff만 포함합니다.
- 브랜치 생성 예시는 기준 ref와 이름을 실제 값으로 확인한 뒤 사용합니다.

```sh
git switch -c <task-branch> <verified-base-ref>
```

- 이름이 이미 존재하면 강제 재생성하지 않고 해당 브랜치의 목적과 worktree를 확인합니다.
- commit 메시지는 [commit 가이드](commit.md), PR 제목과 본문은 [PR 가이드](pr.md)를
  따릅니다. 지침을 읽었다는 이유만으로 commit, push 또는 PR 생성을 실행하지 않습니다.
- push, 브랜치 삭제, force push, rebase, reset 등 이력·원격 상태 변경은 사용자의
  명시적 지시가 있을 때만 수행합니다. 작업이 끝났다고 브랜치를 자동 삭제하지 않습니다.

공식 명령 참고: [git switch](https://git-scm.com/docs/git-switch),
[git worktree](https://git-scm.com/docs/git-worktree).
