-- JOIN 참여자 슬롯 원자 획득 (#73)
-- 검사(빈 슬롯인가)와 기록(내가 차지)을 Redis 안에서 한 단위로 수행해
-- read-modify-write 경합(동시 JOIN 시 둘 다 성공)을 차단한다.
-- KEYS[1]=session:{code}, ARGV[1]=userId, ARGV[2]=socketId, ARGV[3]=TTL(초)

local raw = redis.call('GET', KEYS[1])
if not raw then
    return 'NOT_FOUND'
end

local s = cjson.decode(raw)
local uid = tonumber(ARGV[1])

if s.ownerUserId == uid then
    return 'TAKEOVER_OWNER'
end

-- JSON의 null은 cjson.null로 디코딩된다 (키 자체가 없으면 nil)
if s.participantUserId ~= cjson.null and s.participantUserId ~= nil then
    if s.participantUserId == uid then
        return 'TAKEOVER_PARTICIPANT'
    end
    return 'OCCUPIED'
end

s.participantUserId = uid
s.participantSocketId = ARGV[2]
s.status = 'CONNECTED'
redis.call('SET', KEYS[1], cjson.encode(s), 'EX', tonumber(ARGV[3]))
return 'CLAIMED'
