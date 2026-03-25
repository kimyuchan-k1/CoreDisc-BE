package com.coredisc.security.jwt;

import com.coredisc.common.apiPayload.ApiResponse;
import com.coredisc.common.apiPayload.code.BaseErrorCode;
import com.coredisc.common.apiPayload.status.ErrorStatus;
import com.coredisc.common.exception.GeneralException;
import com.coredisc.common.exception.handler.AuthHandler;
import com.coredisc.common.util.RedisUtil;
import com.coredisc.security.auth.PrincipalDetailsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final RedisUtil redisUtil;
    private final PrincipalDetailsService principalDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        try {
            String accessToken = jwtProvider.resolveAccessToken(request);

            // JWT 유효성 검증
            if(accessToken != null && jwtProvider.validateAccessToken(accessToken)) {
                // 로그아웃된 토큰 차단 (Redis 장애 시 get()이 null 반환 → 블랙리스트 아님으로 처리)
                String blackListValue = (String)redisUtil.get(accessToken);
                if(blackListValue != null && blackListValue.equals("logout")) {
                    throw new AuthHandler(ErrorStatus.TOKEN_LOGGED_OUT);
                }

                String username = jwtProvider.getUsername(accessToken);
                // 유저와 토큰 일치 시 userDetails 생성
                UserDetails userDetails = principalDetailsService.loadUserByUsername(username);
                if(userDetails != null) {
                    // 인증된 사용자 정보(userDetails, password, role)을 바탕으로 Authentication 객체 생성
                    Authentication authentication = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            userDetails.getPassword(),
                            userDetails.getAuthorities()
                            );
                    // 생성한 Authentication을 현재 Request의 Security Context에 등록
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    throw new AuthHandler(ErrorStatus.MEMBER_NOT_FOUND);
                }
            }
            // 다음 필터로 넘기기
            filterChain.doFilter(request, response);
        } catch (GeneralException e) { // 필터 단계 예외는 전역 예외 처리기까지 도달하지 않으므로, 직접 응답 구성이 필요
            BaseErrorCode code = e.getCode();
            response.setContentType("application/json; charset=UTF-8");
            response.setStatus(code.getReasonHttpStatus().getHttpStatus().value());

            ApiResponse<Object> errorResponse = ApiResponse.onFailure(
                    code.getReasonHttpStatus().getCode(),
                    code.getReasonHttpStatus().getMessage(),
                    e.getMessage());

            ObjectMapper om = new ObjectMapper();
            om.writeValue(response.getOutputStream(), errorResponse);
        }
    }
}
