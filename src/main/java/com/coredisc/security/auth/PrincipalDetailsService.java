package com.coredisc.security.auth;

import com.coredisc.common.apiPayload.status.ErrorStatus;
import com.coredisc.common.exception.handler.AuthHandler;
import com.coredisc.domain.member.Member;
import com.coredisc.infrastructure.repository.member.JpaMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PrincipalDetailsService implements UserDetailsService {

    private final JpaMemberRepository memberRepository;

    @Override
    @Cacheable(value = "userDetails", key = "#username")
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        Member member = memberRepository.findByUsername(username)
                .orElseThrow(() -> new AuthHandler(ErrorStatus.MEMBER_NOT_FOUND));

        return new PrincipalDetails(member);
    }

    @CacheEvict(value = "userDetails", key = "#username")
    public void evictUserCache(String username) {
        // 사용자 정보 변경 시 캐시 무효화
    }
}
