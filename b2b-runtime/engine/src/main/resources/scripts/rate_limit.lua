-- 고정 창(fixed window) 레이트리밋 카운터 (#125)
-- INCR과 EXPIRE를 원자로 묶어 "TTL 없는 카운터 키"가 영구 잔존하는 틈을 막는다.
-- 창이 끝나면 키가 TTL로 사라지므로 청소가 공짜이고, 다중 인스턴스에서 총량이 정확하다.
-- KEYS[1]=카운터 키, ARGV[1]=창 길이(초). 반환: 현재 창의 누적 호출 수
local count = redis.call('INCR', KEYS[1])
if count == 1 then
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
end
return count
