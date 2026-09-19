-- All keys share a room hash tag. Preserve envelope and sequence values as strings.
local existing = redis.call('GET', KEYS[2])
if existing then return existing end

local function less(a, b)
    local am, as = string.match(a, '^(%d+)%-(%d+)$')
    local bm, bs = string.match(b, '^(%d+)%-(%d+)$')
    if #am ~= #bm then return #am < #bm end
    if am ~= bm then return am < bm end
    if #as ~= #bs then return #as < #bs end
    return as < bs
end

local limit = tonumber(ARGV[1])
if limit > 0 and redis.call('XLEN', KEYS[1]) >= limit then
    local groups = redis.call('XINFO', 'GROUPS', KEYS[1])
    local writer, fanout, boundary = false, false, nil
    local pendingBoundaries = {}
    for _, fields in ipairs(groups) do
        local group = {}
        for i = 1, #fields, 2 do group[fields[i]] = fields[i + 1] end
        if group.name == ARGV[2] then writer = true end
        if group.name == ARGV[8] then fanout = true end
        local cutoff = group['last-delivered-id']
        local pending = redis.call('XPENDING', KEYS[1], group.name)
        if pending[1] > 0 then
            pendingBoundaries[pending[2]] = true
            if less(pending[2], cutoff) then cutoff = pending[2] end
        end
        if not boundary or less(cutoff, boundary) then boundary = cutoff end
    end
    -- Never discard unread entries, PEL entries, or data before both consumers exist.
    if writer and fanout and boundary then
        redis.call('XTRIM', KEYS[1], 'MINID', '=', boundary)
        if not pendingBoundaries[boundary] then redis.call('XDEL', KEYS[1], boundary) end
    end
    if redis.call('XLEN', KEYS[1]) >= limit then return 'FULL' end
end
redis.call('XADD', KEYS[1], '*', 'messageId', ARGV[3], 'chatRoomId', ARGV[4],
    'roomSeq', ARGV[5], 'streamShard', ARGV[6], 'payload', ARGV[7])
redis.call('SET', KEYS[2], ARGV[7])
return ARGV[7]
