package com.drone.server.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JWT 工具类（配置绑定 + 详细异常日志）
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtUtil {

    private String secret;               // 密钥字符串
    private long expiration;              // access token 过期时间（毫秒）
    private long refreshExpiration;       // refresh token 过期时间（毫秒）

    private SecretKey cachedSecretKey;    // 缓存 SecretKey

    private SecretKey getSigningKey() {
        if (cachedSecretKey == null) {
            byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
            cachedSecretKey = Keys.hmacShaKeyFor(keyBytes);
        }
        return cachedSecretKey;
    }

    // ========== 生成 Token ==========

    public String generateToken(String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("username", username);
        return generateToken(claims);
    }

    public String generateToken(Map<String, Object> claims) {
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String generateRefreshToken(String username) {
        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + refreshExpiration))
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
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String extractUsername(String token) throws JwtException {
        return extractClaim(token, claims -> claims.get("username", String.class));
    }

    public Date extractExpiration(String token) throws JwtException {
        return extractClaim(token, Claims::getExpiration);
    }

    // 验证 Token

    public boolean validateToken(String token) {
        return validateToken(token, null);
    }

    public boolean validateToken(String token, String expectedUsername) {
        try {
            Claims claims = extractAllClaims(token);
            if (claims.getExpiration().before(new Date())) {
                log.warn("Token expired: {}", token);
                return false;
            }
            if (expectedUsername != null) {
                String username = claims.get("username", String.class);
                if (!expectedUsername.equals(username)) {
                    log.warn("Username mismatch: expected {}, but got {}", expectedUsername, username);
                    return false;
                }
            }
            return true;
        } catch (ExpiredJwtException e) {
            log.error("Token expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.error("Unsupported JWT: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.error("Malformed JWT: {}", e.getMessage());
        } catch (SignatureException e) {
            log.error("Invalid signature: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.error("JWT claims string is empty: {}", e.getMessage());
        } catch (JwtException e) {
            log.error("JWT validation error: {}", e.getMessage());
        }
        return false;
    }

    public boolean isTokenExpired(String token) {
        try {
            Date expiration = extractExpiration(token);
            return expiration.before(new Date());
        } catch (JwtException e) {
            log.error("Failed to extract expiration: {}", e.getMessage());
            return true;
        }
    }

    //刷新 Access Token

    public String refreshAccessToken(String refreshToken) {
        if (!validateToken(refreshToken)) {
            throw new JwtException("Invalid refresh token");
        }
        String username = extractUsername(refreshToken);
        return generateToken(username);
    }


    public void setSecret(String secret) {
        this.secret = secret;
    }

    public void setExpiration(long expiration) {
        this.expiration = expiration;
    }

    public void setRefreshExpiration(long refreshExpiration) {
        this.refreshExpiration = refreshExpiration;
    }
}