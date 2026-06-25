FROM postgres:17.9-alpine

# aws-cli 설치 후 archive 작업 디렉터리를 postgres 소유로 준비하고 비루트로 실행한다.
# (named volume은 비어 있을 때 이미지 마운트 지점의 소유권/권한을 그대로 상속받는다.)
RUN apk add --no-cache aws-cli \
  && mkdir -p /archive \
  && chown postgres:postgres /archive

USER postgres
