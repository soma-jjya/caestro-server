# AWS 제공 Lambda Python 베이스 이미지 (Linux x86_64, Lambda 런타임 내장)
# → 로컬 아키텍처와 무관하게 Lambda 환경 그대로 재현된다.
FROM public.ecr.aws/lambda/python:3.12

# 의존성 설치 (Pillow). LAMBDA_TASK_ROOT = /var/task
COPY requirements.txt ${LAMBDA_TASK_ROOT}/
RUN pip install --no-cache-dir -r ${LAMBDA_TASK_ROOT}/requirements.txt

# 함수 코드 복사
COPY handler.py resize.py ${LAMBDA_TASK_ROOT}/

# 핸들러 지정: 파일명.함수명 (handler.py 의 handler 함수)
CMD ["handler.handler"]
