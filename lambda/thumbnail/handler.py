"""
썸네일 생성 Lambda 핸들러.
S3의 assets/<ver>/orig/<shot>.jpg 업로드를 트리거로 thumb(256)·preview(640)를 생성한다.
계약서: handoff-app/LAMBDA_THUMBS.md

트리거: EventBridge 규칙 {"wildcard": "assets/*/orig/*.jpg"} 권장.
        (코드에도 /orig/ 가드를 두어 무한 재귀를 이중 방지)
"""
import boto3
from resize import OUTPUTS, resize_to_jpeg

s3 = boto3.client("s3")


def _parse_event(event: dict):
    """
    이벤트에서 (bucket, key)를 추출한다. EventBridge와 S3 알림 두 형식을 모두 지원한다.
    """
    # EventBridge 형식
    if "detail" in event:
        detail = event["detail"]
        return detail["bucket"]["name"], detail["object"]["key"]
    # S3 알림(Records) 형식
    if "Records" in event:
        record = event["Records"][0]["s3"]
        return record["bucket"]["name"], record["object"]["key"]
    raise ValueError(f"지원하지 않는 이벤트 형식: {list(event.keys())}")


def handler(event, context):
    bucket, key = _parse_event(event)

    # ── 안전장치: orig가 아니면 무시 (자신이 쓴 thumb/preview 재트리거 → 무한 재귀 방지) ──
    if "/orig/" not in key:
        print(f"skip (not orig): {key}")
        return {"status": "skipped", "key": key}

    # 1. 원본 다운로드 (메모리)
    obj = s3.get_object(Bucket=bucket, Key=key)
    original_bytes = obj["Body"].read()

    # 2. 각 출력(thumb, preview) 생성 후 S3 업로드
    created = []
    for folder, size, quality in OUTPUTS:
        result_bytes = resize_to_jpeg(original_bytes, size, quality)

        # orig → thumb/preview 로 폴더만 교체 (스템·확장자 동일)
        out_key = key.replace("/orig/", f"/{folder}/")

        # 멱등: 같은 키면 덮어쓴다. 헤더는 계약서 스펙 그대로.
        s3.put_object(
            Bucket=bucket,
            Key=out_key,
            Body=result_bytes,
            ContentType="image/jpeg",
            CacheControl="public, max-age=31536000, immutable",
        )
        created.append(out_key)
        print(f"created: {out_key} ({size}px q{quality}, {len(result_bytes) / 1024:.1f}KB)")

    return {"status": "ok", "source": key, "created": created}
