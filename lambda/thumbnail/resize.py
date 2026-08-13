"""
이미지 리사이즈 순수 로직 (AWS 의존 없음).
handler.py(Lambda)와 local_test.py(로컬 테스트)가 공통으로 사용한다.
AWS 없이 이 파일만으로 리사이즈 품질/크기를 검증할 수 있다.
"""
import io
from PIL import Image

# 계약서(LAMBDA_THUMBS.md) 출력 스펙: (폴더명, 긴 변 최대 픽셀, JPEG 품질)
OUTPUTS = [
    ("thumb", 256, 82),
    ("preview", 640, 80),
]


def resize_to_jpeg(image_bytes: bytes, size: int, quality: int) -> bytes:
    """
    원본 이미지 바이트를 받아 지정 크기로 축소한 JPEG 바이트를 반환한다.

    :param image_bytes: 원본 이미지 바이트 (orig)
    :param size: 긴 변 최대 픽셀 (예: 256) — 비율은 유지된다
    :param quality: JPEG 품질 (1~100, 높을수록 고품질·큰 용량)
    :return: 축소된 JPEG 바이트
    """
    # 1. 바이트 → 이미지 객체로 열기
    img = Image.open(io.BytesIO(image_bytes))

    # 2. RGB로 변환 (PNG 투명(RGBA)이나 흑백 이미지도 JPEG로 저장 가능하게)
    img = img.convert("RGB")

    # 3. 비율 유지하며 축소 (긴 변이 size 안에 들어가게). LANCZOS = 고품질 축소.
    img.thumbnail((size, size), Image.LANCZOS)

    # 4. 결과를 메모리에 JPEG로 저장
    buffer = io.BytesIO()
    img.save(buffer, format="JPEG", quality=quality, optimize=True)
    buffer.seek(0)
    return buffer.getvalue()
