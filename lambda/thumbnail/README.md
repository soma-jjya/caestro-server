# 썸네일 생성 Lambda

S3 `assets/<ver>/orig/<shot>.jpg` 업로드를 트리거로 thumb(256px)·preview(640px)를 자동 생성한다.
계약서: `handoff-app/LAMBDA_THUMBS.md`

## 구성

| 파일 | 역할 |
|---|---|
| `resize.py` | Pillow 리사이즈 순수 로직 (AWS 무관, 로컬 테스트 가능) |
| `handler.py` | Lambda 진입점. `/orig/` 가드 → S3 읽기 → 리사이즈 → thumb/preview 쓰기 |
| `local_test.py` | 로컬 검증 (AWS 없이 크기·품질 확인) |
| `Dockerfile` | AWS Lambda Python 베이스 이미지 (컨테이너 배포) |

## 아키텍처

```
S3 assets/<ver>/orig/<shot>.jpg 업로드
  → EventBridge 규칙(assets/*/orig/*.jpg) → Lambda(peakpic-thumbnail)
  → orig 리사이즈 → thumb(256/q82)·preview(640/q80) S3 저장
```

- 백엔드(EC2/Spring)와 완전 분리. 별도 ECR 리포·별도 실행 환경.
- `/orig/` 가드로 무한재귀 방지(자신이 쓴 thumb/preview 재트리거 차단).
- 멱등: 같은 키 덮어쓰기 안전.

## AWS 리소스 (이미 생성됨)

- ECR 리포: `peakpic-thumbnail` (계정 063562646825, ap-northeast-2)
- Lambda 함수: `peakpic-thumbnail` (컨테이너 이미지, 메모리 512MB / 타임아웃 30초)
- 실행 역할: `peakpic-thumbnail-role` (S3 orig read, thumb/preview write)
- EventBridge 규칙: `thumbnail-on-orig-upload`
- S3 버킷 속성에서 EventBridge 알림 ON

## 로컬 테스트

```bash
cd lambda/thumbnail
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
python3 local_test.py <원본이미지.jpg>   # out_thumb.jpg, out_preview.jpg 생성 + 크기 출력
```

## 재배포 (코드 변경 시)

> ⚠️ CLI는 PeakPic 계정 프로파일(`--profile peakpic`)로 실행해야 한다. (기본 프로파일은 다른 계정)
> ⚠️ Lambda 호환을 위해 반드시 `--provenance=false --output ...oci-mediatypes=false` (Docker v2 형식).
> buildx 기본(OCI/manifest-list)은 Lambda가 못 읽는다.

```bash
cd lambda/thumbnail

# 1. ECR 로그인
aws ecr get-login-password --region ap-northeast-2 --profile peakpic \
  | docker login --username AWS --password-stdin 063562646825.dkr.ecr.ap-northeast-2.amazonaws.com

# 2. Lambda 호환 형식으로 빌드 + push
docker buildx build \
  --platform linux/amd64 \
  --provenance=false \
  --output type=image,oci-mediatypes=false \
  -t 063562646825.dkr.ecr.ap-northeast-2.amazonaws.com/peakpic-thumbnail:latest \
  --push .

# 3. Lambda가 새 이미지를 쓰도록 갱신 (⚠️ 이 단계 안 하면 Lambda는 옛 이미지 그대로)
aws lambda update-function-code \
  --function-name peakpic-thumbnail \
  --image-uri 063562646825.dkr.ecr.ap-northeast-2.amazonaws.com/peakpic-thumbnail:latest \
  --region ap-northeast-2 --profile peakpic
```

콘솔로 3번을 하려면: Lambda → `peakpic-thumbnail` → 이미지 → "새 이미지 배포" → `latest` 재선택.

## 검증

```bash
# v011 orig 하나 재업로드(덮어쓰기, 안전) → 몇 초 뒤 thumb/preview 생성 확인
# Lambda → 모니터링 → CloudWatch 로그에 "created: .../thumb/... .../preview/..." 확인
```
