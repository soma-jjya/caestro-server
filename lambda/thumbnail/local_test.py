"""
로컬 테스트: 원본 이미지 하나를 받아 thumb/preview를 만들어 파일로 저장하고 크기를 출력한다.
AWS 없이 리사이즈 품질과 파일 크기를 눈으로 확인하기 위한 용도.

사용법:
    python3 local_test.py <원본이미지경로>
    예) python3 local_test.py sample.jpg

결과물: out_thumb.jpg, out_preview.jpg (같은 폴더에 생성)
"""
import sys
import os
from resize import OUTPUTS, resize_to_jpeg


def main():
    # 1. 인자로 받은 원본 이미지 경로 확인
    if len(sys.argv) < 2:
        print("사용법: python3 local_test.py <원본이미지경로>")
        sys.exit(1)

    src_path = sys.argv[1]
    if not os.path.exists(src_path):
        print(f"파일을 찾을 수 없습니다: {src_path}")
        sys.exit(1)

    # 2. 원본 읽기
    with open(src_path, "rb") as f:
        original_bytes = f.read()
    print(f"원본: {src_path}  ({len(original_bytes) / 1024:.1f} KB)")

    # 3. 각 출력(thumb, preview) 생성 후 파일로 저장 + 크기 출력
    for folder, size, quality in OUTPUTS:
        result_bytes = resize_to_jpeg(original_bytes, size, quality)
        out_path = f"out_{folder}.jpg"
        with open(out_path, "wb") as f:
            f.write(result_bytes)
        print(f"{folder:8s} → {out_path}  ({size}px, q{quality})  {len(result_bytes) / 1024:.1f} KB")


if __name__ == "__main__":
    main()
