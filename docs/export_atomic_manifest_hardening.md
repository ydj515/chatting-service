# Admin Export Atomic Manifest Hardening

이 문서는 admin message export의 현재 resume checkpoint 방식에서 남는 duplicate window를 줄이기 위한 Production Hardening task를 정의한다.

## 결론

현재 구현된 chunk pagination + cursor checkpoint는 운영 전 필수 기능 완료 기준을 만족한다. Atomic write/manifest 방식은 현재 완료 조건이 아니라, export 결과의 정확성이 감사/법무/정산 수준으로 필요해질 때 적용할 Production Hardening task로 분리한다.

## 현재 방식

현재 admin export worker는 다음 순서로 동작한다.

1. `admin_message_export_jobs`에서 pending job을 claim한다.
2. PostgreSQL canonical store에서 `exportChunkSize` 단위로 메시지를 읽는다.
3. CSV 파일에 chunk를 append한다.
4. append가 끝난 뒤 `cursor_token`, `exported_rows`, `output_uri` checkpoint를 저장한다.
5. job이 재시도되면 checkpoint cursor와 기존 output file에서 이어 쓴다.

이 방식은 데이터 유실 가능성을 낮추고, worker crash 이후 재개할 수 있다.

## 남는 문제

다음 장애 창에서는 마지막 chunk가 중복될 수 있다.

```text
chunk append 성공
  -> DB checkpoint 저장 전 worker crash
  -> job requeue
  -> 이전 checkpoint부터 재시도
  -> 마지막 chunk 중복 append 가능
```

이는 데이터 유실 문제가 아니라 duplicate 가능성 문제다. 운영자가 재생성하거나 output file을 정리하고 재시도할 수 있으므로 현재 Phase 5/7 완료 조건에는 포함하지 않는다.

## 적용 시점

다음 조건 중 하나라도 true가 되면 atomic manifest hardening을 Production Hardening gate로 승격한다.

- export 결과가 감사, 법무, 정산, 과금, 증빙 자료로 사용된다.
- export 중복 row가 운영자 수동 정리로 처리하기 어려운 수준이 된다.
- export job retry/requeue 빈도가 증가해 duplicate window가 실제 incident로 관측된다.
- Object Storage 연동 이후 part file과 final object의 원자적 공개가 필요해진다.
- export output을 외부 고객 또는 규제기관에 그대로 전달해야 한다.

## 목표 구조

manifest 기반 export는 chunk 파일과 최종 파일 공개를 분리한다.

```text
admin_message_export_jobs
  job_id
  status
  manifest_uri
  final_output_uri
  cursor_token
  exported_rows

export-1/
  manifest.json
  parts/
    part-000001.csv
    part-000002.csv
  final/
    export-1.csv
```

각 part는 독립적으로 완성된 뒤 manifest에 기록한다. 최종 CSV는 manifest에 기록된 complete part만 assemble해 만든다.

## Manifest 예시

```json
{
  "jobId": "export-1",
  "format": "csv",
  "schemaVersion": 1,
  "parts": [
    {
      "partNumber": 1,
      "uri": "s3://chat-export/export-1/parts/part-000001.csv",
      "rowCount": 1000,
      "cursorToken": "opaque-cursor",
      "checksumSha256": "..."
    }
  ],
  "exportedRows": 1000,
  "finalized": false
}
```

## Worker Flow

1. job claim 시 manifest가 없으면 새 manifest를 만든다.
2. 다음 part number와 cursor를 manifest에서 계산한다.
3. chunk를 임시 파일에 쓴다.
4. 임시 파일을 checksum 검증 후 part 경로로 atomic move 또는 Object Storage conditional put 한다.
5. part metadata를 manifest에 append한다.
6. DB checkpoint를 manifest URI와 exported row count로 갱신한다.
7. 더 이상 읽을 row가 없으면 manifest에 기록된 part만 순서대로 assemble한다.
8. final object를 atomic publish한 뒤 job을 `COMPLETED`로 전환한다.

## Resume Flow

재시도 시 worker는 DB checkpoint와 manifest를 함께 읽는다.

- manifest에 기록된 complete part는 다시 쓰지 않는다.
- manifest에 없는 임시 파일은 폐기한다.
- 마지막 complete part의 cursorToken부터 다음 chunk를 읽는다.
- final object가 이미 존재하고 checksum이 맞으면 idempotent하게 `COMPLETED`로 전환한다.

## 완료 기준

- chunk append와 checkpoint 저장 사이 crash가 발생해도 complete part 중복이 생기지 않는다.
- manifest에 기록되지 않은 partial file은 resume 대상에서 제외된다.
- final CSV는 manifest에 기록된 complete part만 포함한다.
- final output publish는 idempotent하다.
- manifest checksum mismatch가 발생하면 job은 fail-closed로 실패한다.
- retry/requeue 후 같은 job을 여러 번 실행해도 최종 row count가 변하지 않는다.

## 관측 지표

- `chat.admin.export.parts.created`
- `chat.admin.export.parts.reused`
- `chat.admin.export.manifest.write.failures`
- `chat.admin.export.finalize.failures`
- `chat.admin.export.duplicate_rows.detected`
- `chat.admin.export.resume.count`

## 복잡도

- 시간 복잡도: 전체 export 대상 `M`건 기준 `O(M)`
- 공간 복잡도: worker memory 기준 `O(chunkSize)`, manifest metadata 기준 `O(partCount)`

## 주의사항

> - manifest 방식은 현재 checkpoint 방식보다 정확성은 높지만 Object Storage, checksum, part lifecycle 관리가 추가된다.
> - local file system에서는 rename이 atomic이어도 Object Storage에서는 overwrite/rename semantics가 다르므로 conditional put 또는 versioned object 전략을 검토해야 한다.
> - final object assemble 중 실패하면 partial final object가 공개되지 않도록 final staging path와 publish path를 분리해야 한다.
> - part cleanup 정책이 없으면 failed/retried job의 임시 파일이 누적될 수 있다.

## 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 현재 chunk checkpoint 유지 | 단순하고 이미 구현되어 있다 | 파일 append 후 checkpoint 전 crash 시 중복 가능 | 현재 기본값 |
| DB에 export row idempotency table 저장 | 중복 방지가 강하다 | export 대상 row 수만큼 DB write가 늘어 운영 DB에 부담 | 비추천 |
| manifest + part file 방식 | 중복 방지와 resume 안정성이 좋다 | Object Storage/manifest 운영 복잡도 증가 | Production Hardening 후보 |
| final object만 재생성 | 구현이 비교적 단순하다 | 대량 export retry 비용이 커진다 | 데이터 규모가 작을 때만 가능 |
