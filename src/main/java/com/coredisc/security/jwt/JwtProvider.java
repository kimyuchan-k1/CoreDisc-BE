package com.coredisc.security.jwt;

import com.coredisc.common.apiPayload.status.ErrorStatus;
import com.coredisc.common.exception.handler.AuthHandler;
import com.coredisc.common.util.RedisUtil;
import com.coredisc.presentation.dto.jwt.JwtDTO;
import com.coredisc.security.auth.PrincipalDetails;
import com.coredisc.security.auth.PrincipalDetailsService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class JwtProvider {

    private final PrincipalDetailsService principalDetailsService;
    private final SecretKey secretKey; // JWT 서명을 위한 비밀 키
    private final Long accessExpiration;
    private final Long refreshExpiration;
    private final RedisUtil redisUtil;


    public JwtProvider(PrincipalDetailsService principalDetailsService,
                       @Value("${spring.jwt.secret}")
                       String secretKey,
                       @Value("${spring.jwt.token.access-expiration-time}")
                       Long accessExpiration,
                       @Value("${spring.jwt.token.refresh-expiration-time}")
                       Long refreshExpiration,
                       RedisUtil redisUtil) {
        this.principalDetailsService = principalDetailsService;
        // 문자열 secretKey를 바이트로 변환 후, SecretKey 객체로 변환
        this.secretKey = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
        this.accessExpiration = accessExpiration;
        this.refreshExpiration = refreshExpiration;
        this.redisUtil = redisUtil;
    }

    // AccessToken 생성
    public String createAccessToken(PrincipalDetails principalDetails, Long memberId) {

        Instant issuedAt = Instant.now(); // 현재 시간
        Instant expiredAt = issuedAt.plusMillis(accessExpiration); // 만료 시간

        return Jwts.builder()
                .header()
                    .add("alg", "HS256")
                    .add("typ", "JWT")
                    .and()
                .subject(principalDetails.getUsername())
                .claim("id", memberId)
                .claim("tokenType", "access") // 토큰 타입 명시
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiredAt))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    // RefreshToken 생성
    public String createRefreshToken(PrincipalDetails principalDetails, Long memberId) {

        Instant issuedAt = Instant.now(); // 현재 시간
        Instant expiredAt = issuedAt.plusMillis(refreshExpiration); // 만료 시간

        String refreshToken = Jwts.builder()
                .header()
                    .add("alg", "HS256")
                    .add("typ", "JWT")
                    .and()
                .subject(principalDetails.getUsername())
                .claim("id", memberId)
                .claim("tokenType", "refresh") // 토큰 타입 명시
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiredAt))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();

        redisUtil.set(principalDetails.getUsername(), refreshToken);
        redisUtil.expire(principalDetails.getUsername(), refreshExpiration, TimeUnit.MILLISECONDS);

        return refreshToken;
    }

    // 헤더에서 토큰 추출
    public String resolveAccessToken(HttpServletRequest request) {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }

        return header.split(" ")[1];
    }

    // 토큰의 클레임 가져오기
    public Jws<Claims> getClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
        } catch (Exception e) {
            throw new AuthHandler(ErrorStatus.INVALID_TOKEN);
        }
    }

    // AccessToken 유효성 확인
    public boolean validateAccessToken(String token) {

        try {
            Jws<Claims> claims = getClaims(token);

            String tokenType = claims.getPayload().get("tokenType", String.class);
            if(!tokenType.equals("access")) {
                throw new AuthHandler(ErrorStatus.INVALID_TOKEN);
            }
            // 토큰의 만료 시간이 현재 시간보다 이후인지 확인 (만료 전이면 true)
            return claims.getPayload().getExpiration().after(Date.from(Instant.now()));
        } catch (JwtException e) {
            log.error(e.getMessage());
            return false;
        } catch (Exception e) {
            log.error(e.getMessage() + ": 토큰이 유효하지 않습니다.");
            return false;
        }
    }

    // username 추출
    public String getUsername(String token) {
        return getClaims(token).getPayload().getSubject();
    }

    // RefreshToken 유효성 확인
    public void validateRefreshToken(String refreshToken) {

        try {
            Jws<Claims> claims = getClaims(refreshToken);

            String tokenType = claims.getPayload().get("tokenType", String.class);
            if(!tokenType.equals("refresh")) {
                throw new AuthHandler(ErrorStatus.INVALID_TOKEN);
            }

            String username = getUsername(refreshToken);

            // redis 확인 (Redis 장애 시 exists()가 false 반환 → refresh 재발급 불가)
            if(!redisUtil.exists(username)) {
                throw new AuthHandler(ErrorStatus.INVALID_TOKEN);
            }
        } catch (JwtException e) {
            log.error("JWT validation error: " + e.getMessage());
            throw new AuthHandler(ErrorStatus.INVALID_TOKEN);
        } catch (Exception e) {
            throw new AuthHandler(ErrorStatus.INVALID_TOKEN);
        }
    }

    // id(PK) 추출
    public Long getId(String token) {
        return getClaims(token).getPayload().get("id", Long.class);
    }

    // 토큰 재발급
    public JwtDTO reissueToken(String refreshToken) throws SignatureException {

        UserDetails userDetails = principalDetailsService.loadUserByUsername(getUsername(refreshToken));
        Long memberId = getId(refreshToken);

        return new JwtDTO(
                createAccessToken((PrincipalDetails) userDetails, memberId),
                createRefreshToken((PrincipalDetails) userDetails, memberId)
        );
    }

    // 토큰 유효시간 반환
    public Long getExpTime(String token) {
        return getClaims(token).getPayload().getExpiration().getTime();
    }

    // 토큰의 남은 유효시간 반환
    public Long getRemainingExpiration(String token) {

        Jws<Claims> claims = getClaims(token);
        Date expiration = claims.getPayload().getExpiration();

        return expiration.getTime() - System.currentTimeMillis();
    }
}
