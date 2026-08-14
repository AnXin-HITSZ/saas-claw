local incrementValue = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local currentWindowWeight = tonumber(ARGV[3])

local totalCount = 0

for i = 1, 6 do
    local windowCount = tonumber(redis.call('GET', KEYS[i]) or '0')
    if windowCount > 0 then
        if i == 1 then
            totalCount = totalCount + windowCount * currentWindowWeight
        elseif i == 6 then
            totalCount = totalCount + windowCount * (1 - currentWindowWeight)
        else
            totalCount = totalCount + windowCount
        end
    end
end

local projected = totalCount + incrementValue;
if projected > limit then
    return -math.floor(projected)
end

local newCount = redis.call('INCRBY', KEYS[1], incrementValue);
if newCount == incrementValue then
    redis.call('EXPIRE', KEYS[1], 70)
end
return math.floor(projected)