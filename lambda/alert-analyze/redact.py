"""도구 결과를 모델에 보내기 전 개인정보·비밀값을 지운다. 로그·액세스 로그 어디에 섞여 있든 같은 규칙."""

import re

# (패턴, 대체 문자열). 순서가 중요하다 — Bearer 토큰을 JWT 보다 먼저 잡아야 접두어까지 지워진다.
_RULES = [
    (re.compile(r"(?i)(authorization|x-studio-key|x-api-key|cookie)\s*[:=]\s*[^\s,;\"']+"), r"\1: [REDACTED]"),
    (re.compile(r"(?i)bearer\s+[A-Za-z0-9._~+/-]+=*"), "Bearer [REDACTED]"),
    (re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"), "[JWT]"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "[AWS_KEY]"),
    (re.compile(r"\bsk-ant-[A-Za-z0-9_-]{10,}"), "[API_KEY]"),
    (re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "[EMAIL]"),
    (re.compile(r"\b01[016789][-.\s]?\d{3,4}[-.\s]?\d{4}\b"), "[PHONE]"),
    (re.compile(r"\+82[-.\s]?1[016789][-.\s]?\d{3,4}[-.\s]?\d{4}\b"), "[PHONE]"),
]


def redact(text):
    """문자열에서 토큰·키·이메일·전화번호를 치환한다. 문자열이 아니면 그대로."""
    if not isinstance(text, str):
        return text
    for pattern, repl in _RULES:
        text = pattern.sub(repl, text)
    return text


def redact_deep(value):
    """dict/list 를 재귀로 돌며 모든 문자열에 redact 를 적용한다."""
    if isinstance(value, dict):
        return {k: redact_deep(v) for k, v in value.items()}
    if isinstance(value, list):
        return [redact_deep(v) for v in value]
    return redact(value)
