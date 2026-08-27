-- relay 핫패스의 조회+수명연장을 단일 왕복으로 처리 (#98)
-- 기존: GET socket → GET session → PEXPIRE×3 을 클라이언트가 5회 순차 왕복
-- 변경: 의존 사슬(소켓→코드→JSON→소켓ID들)을 Redis 안에서 실행하고 최종 JSON만 반환
--
-- KEYS[1] = socket:{발신 소켓ID}
-- ARGV[1] = 수명(ms)
-- 반환: 세션 JSON 원문 / 소켓 미소속·세션 없음이면 nil

local code = redis.call('GET', KEYS[1])
if not code then
    return nil
end

local sessionKey = 'session:' .. code
local raw = redis.call('GET', sessionKey)
if not raw then
    return nil
end

-- 슬라이딩 수명: 기존 동작(중계마다 방·소켓 10분 연장)을 그대로 재현
local info = cjson.decode(raw)
redis.call('PEXPIRE', sessionKey, ARGV[1])
if info.ownerSocketId ~= nil and info.ownerSocketId ~= cjson.null then
    redis.call('PEXPIRE', 'socket:' .. info.ownerSocketId, ARGV[1])
end
if info.participantSocketId ~= nil and info.participantSocketId ~= cjson.null then
    redis.call('PEXPIRE', 'socket:' .. info.participantSocketId, ARGV[1])
end

return raw
