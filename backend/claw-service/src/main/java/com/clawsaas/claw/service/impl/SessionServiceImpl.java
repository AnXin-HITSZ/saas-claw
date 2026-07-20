package com.clawsaas.claw.service.impl;

import com.clawsaas.claw.domain.AuthenticatedPrincipal;
import com.clawsaas.claw.dto.SessionDetailResponse;
import com.clawsaas.claw.dto.SessionMessageResponse;
import com.clawsaas.claw.dto.SessionSummaryResponse;
import com.clawsaas.claw.exception.ApiException;
import com.clawsaas.claw.service.SessionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class SessionServiceImpl implements SessionService {
    private static final int SESSION_TTL_SECONDS = 30 * 24 * 3600; // 30 days

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public SessionServiceImpl(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SessionSummaryResponse> listByClaw(String clawId, Authentication authentication) {
        Set<String> sessionIds = redis.opsForZSet().reverseRange(sessionsClawKey(clawId), 0, -1);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        List<SessionSummaryResponse> result = new ArrayList<>();
        for (String sessionId : sessionIds) {
            Map<String, String> meta = getMeta(sessionId);
            if (meta.isEmpty()) {
                continue;
            }
            if (!isAdmin(authentication) && !Objects.equals(meta.get("userId"), actorId(authentication))) {
                continue;
            }
            result.add(toSummary(sessionId, meta));
        }
        return result;
    }

    @Override
    public List<SessionSummaryResponse> listByUser(Authentication authentication) {
        String userId = actorId(authentication);
        if (isAdmin(authentication)) {
            return scanAllSessions(authentication);
        }
        Set<String> sessionIds = redis.opsForZSet().reverseRange(sessionsUserKey(userId), 0, -1);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        List<SessionSummaryResponse> result = new ArrayList<>();
        for (String sessionId : sessionIds) {
            Map<String, String> meta = getMeta(sessionId);
            if (meta.isEmpty()) {
                continue;
            }
            result.add(toSummary(sessionId, meta));
        }
        return result;
    }

    @Override
    public SessionDetailResponse getDetail(String sessionId, Authentication authentication) {
        requireOwnedAuthentication(sessionId, authentication);
        Map<String, String> meta = getMeta(sessionId);
        List<String> rawMessages = redis.opsForList().range(sessionMessagesKey(sessionId), 0, -1);
        List<SessionMessageResponse> messages = new ArrayList<>();
        if (rawMessages != null) {
            for (String raw : rawMessages) {
                try {
                    messages.add(objectMapper.readValue(raw, SessionMessageResponse.class));
                } catch (JsonProcessingException ignored) {
                    // skip malformed messages
                }
            }
        }
        return new SessionDetailResponse(toSummary(sessionId, meta), messages);
    }

    @Override
    public void requireOwnedByClaw(String sessionId, String clawId) {
        Map<String, String> meta = getMeta(sessionId);
        if (meta.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Session not found");
        }
        String sessionClawId = meta.get("clawId");
        if (sessionClawId == null || !sessionClawId.equals(clawId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Session does not belong to this Claw");
        }
    }

    @Override
    public void requireOwned(String sessionId, AuthenticatedPrincipal principal) {
        boolean isAdmin = principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("user:manage"));
        requireOwnedByUser(sessionId, principal.userId(), isAdmin);
    }

    private void requireOwnedAuthentication(String sessionId, Authentication authentication) {
        requireOwnedByUser(sessionId, actorId(authentication), isAdmin(authentication));
    }

    private void requireOwnedByUser(String sessionId, String userId, boolean isAdmin) {
        Map<String, String> meta = getMeta(sessionId);
        if (meta.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Session not found");
        }
        if (!isAdmin && !Objects.equals(meta.get("userId"), userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Session not found");
        }
    }

    @Override
    public void saveMessage(String sessionId, String userId, String clawId, String clawName,
                            String agentKey, String provider, String model,
                            String role, String content, long timestamp) {
        saveMessage(sessionId, userId, clawId, clawName, agentKey, null, null, provider, model, role, content, timestamp);
    }

    @Override
    public void saveMessage(String sessionId, String userId, String clawId, String clawName,
                            String agentKey, String roleKey, String agentId,
                            String provider, String model,
                            String role, String content, long timestamp) {
        SessionMessageResponse msg = new SessionMessageResponse(role, content, timestamp);
        try {
            String json = objectMapper.writeValueAsString(msg);
            redis.opsForList().rightPush(sessionMessagesKey(sessionId), json);
        } catch (JsonProcessingException ignored) {
            return;
        }

        Map<String, String> meta = new HashMap<>();
        meta.put("userId", userId);
        meta.put("clawId", clawId != null ? clawId : "");
        meta.put("clawName", clawName != null ? clawName : "");
        meta.put("agentKey", agentKey != null ? agentKey : "");
        meta.put("roleKey", roleKey != null ? roleKey : "");
        meta.put("agentId", agentId != null ? agentId : "");
        meta.put("provider", provider != null ? provider : "");
        meta.put("model", model != null ? model : "");

        String existingCount = (String) redis.opsForHash().get(sessionMetaKey(sessionId), "messageCount");
        String existingCreated = (String) redis.opsForHash().get(sessionMetaKey(sessionId), "createdAt");
        int count = existingCount != null ? Integer.parseInt(existingCount) + 1 : 1;

        meta.put("messageCount", String.valueOf(count));
        meta.put("lastActiveAt", Instant.ofEpochMilli(timestamp).atOffset(ZoneOffset.UTC).toString());
        if (existingCreated != null) {
            meta.put("createdAt", existingCreated);
        } else {
            meta.put("createdAt", Instant.ofEpochMilli(timestamp).atOffset(ZoneOffset.UTC).toString());
        }

        redis.opsForHash().putAll(sessionMetaKey(sessionId), metaMapToStringMap(meta));
        redis.expire(sessionMetaKey(sessionId), SESSION_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        redis.expire(sessionMessagesKey(sessionId), SESSION_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);

        double score = (double) timestamp / 1000.0;
        redis.opsForZSet().add(sessionsUserKey(userId), sessionId, score);
        if (clawId != null && !clawId.isBlank()) {
            redis.opsForZSet().add(sessionsClawKey(clawId), sessionId, score);
        }
    }

    @Override
    public void deleteByClaw(String clawId) {
        Set<String> sessionIds = redis.opsForZSet().range(sessionsClawKey(clawId), 0, -1);
        if (sessionIds != null) {
            for (String sessionId : sessionIds) {
                String userId = (String) redis.opsForHash().get(sessionMetaKey(sessionId), "userId");
                redis.delete(sessionMetaKey(sessionId));
                redis.delete(sessionMessagesKey(sessionId));
                if (userId != null) {
                    redis.opsForZSet().remove(sessionsUserKey(userId), sessionId);
                }
            }
        }
        redis.delete(sessionsClawKey(clawId));
    }

    private Map<String, String> getMeta(String sessionId) {
        Map<Object, Object> raw = redis.opsForHash().entries(sessionMetaKey(sessionId));
        Map<String, String> meta = new HashMap<>();
        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            meta.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return meta;
    }

    private SessionSummaryResponse toSummary(String sessionId, Map<String, String> meta) {
        return new SessionSummaryResponse(
                sessionId,
                emptyToNull(meta.get("clawId")),
                emptyToNull(meta.get("clawName")),
                emptyToNull(meta.get("agentKey")),
                emptyToNull(meta.get("provider")),
                emptyToNull(meta.get("model")),
                parseInt(meta.get("messageCount")),
                parseOffsetDateTime(meta.get("createdAt")),
                parseOffsetDateTime(meta.get("lastActiveAt"))
        );
    }

    private List<SessionSummaryResponse> scanAllSessions(Authentication authentication) {
        List<SessionSummaryResponse> result = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match("session:*:meta").count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String sessionId = key.substring("session:".length(), key.length() - ":meta".length());
                Map<String, String> meta = getMeta(sessionId);
                if (!meta.isEmpty()) {
                    result.add(toSummary(sessionId, meta));
                }
            }
        }
        return result;
    }

    private Map<String, String> metaMapToStringMap(Map<String, String> meta) {
        return meta;
    }

    // Key helpers
    private static String sessionsUserKey(String userId) { return "sessions:user:" + userId; }
    private static String sessionsClawKey(String clawId) { return "sessions:claw:" + clawId; }
    private static String sessionMetaKey(String sessionId) { return "session:" + sessionId + ":meta"; }
    private static String sessionMessagesKey(String sessionId) { return "session:" + sessionId + ":messages"; }

    // Utils
    private boolean isAdmin(Authentication authentication) {
        Set<String> authorities = authentication == null ? Set.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return authorities.contains("user:manage");
    }

    private String actorId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private int parseInt(String value) {
        if (value == null) return 0;
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return 0; }
    }

    private OffsetDateTime parseOffsetDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return OffsetDateTime.parse(value); } catch (Exception e) { return null; }
    }
}
