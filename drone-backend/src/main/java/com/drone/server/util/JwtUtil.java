package com.drone.server.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Slf4j
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtUtil {

    @Setter
    private String secret;
    @Setter
    private long expiration;
    @Setter
    private long refreshExpiration;

    private SecretKey cachedSecretKey;

    private SecretKey getSigningKey() {
        if (cachedSecretKey == null) {
            cachedSecretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
        return cachedSecretKey;
    }

    // ========== 生成 Token ==========

    public String generateToken(Long userId, String username, Integer role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("role", role);
        return buildToken(claims, expiration);
    }

    public String generateRefreshToken(Long userId, String username, Integer role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("role", role);
        return buildToken(claims, refreshExpiration);
    }

    private String buildToken(Map<String, Object> claims, long ttlMillis) {
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + ttlMillis))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    // ========== 解析 Claims ==========

    public Claims extractAllClaims(String token) throws JwtException {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) throws JwtException {
        return claimsResolver.apply(extractAllClaims(token));
    }

    public String extractUsername(String token) throws JwtException {
        return extractClaim(token, claims -> claims.get("username", String.class));
    }

    public Long extractUserId(String token) throws JwtException {
        return extractClaim(token, claims -> claims.get("userId", Long.class));
    }

    public Integer extractRole(String token) throws JwtException {
        return extractClaim(token, claims -> claims.get("role", Integer.class));
    }

    // ========== 验证 Token ==========

    public boolean validateToken(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return !claims.getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
            log.warn("Token expired");
        } catch (UnsupportedJwtException e) {
            log.warn("Unsupported JWT");
        } catch (MalformedJwtException e) {
            log.warn("Malformed JWT");
        } catch (SignatureException e) {
            log.warn("Invalid signature");
        } catch (IllegalArgumentException e) {
            log.warn("JWT claims string is empty");
        } catch (JwtException e) {
            log.warn("JWT validation error: {}", e.getMessage());
        }
        return false;
    }

    public boolean isTokenExpired(String token) {
        try {
            return extractExpiration(token).before(new Date());
        } catch (JwtException e) {
            log.warn("Failed to extract expiration");
            return true;
        }
    }

    public Date extractExpiration(String token) throws JwtException {
        return extractClaim(token, Claims::getExpiration);
    }

    // ========== 刷新 Access Token ==========

    public String refreshAccessToken(String refreshToken) {
        if (!validateToken(refreshToken)) {
            log.warn("刷新令牌无效");
            throw new JwtException("Invalid refresh token");
        }
        Long userId = extractUserId(refreshToken);
        String username = extractUsername(refreshToken);
        Integer role = extractRole(refreshToken);
        if (userId == null || username == null || username.isBlank() || role == null) {
            log.warn("刷新令牌中缺少用户信息");
            throw new JwtException("Invalid refresh token");
        }
        return generateToken(userId, username, role);
    }
}
