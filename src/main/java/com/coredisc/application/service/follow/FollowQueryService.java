package com.coredisc.application.service.follow;

import com.coredisc.domain.member.Member;
import com.coredisc.presentation.dto.follow.FollowResponseDTO;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface FollowQueryService {

    // 팔로워 목록 조회
    FollowResponseDTO.FollowerListDTO getFollowers(Member member, Long cursorId, Pageable pageable);

    // 팔로잉 목록 조회
    FollowResponseDTO.FollowingListDTO getFollowings(Member member, Long cursorId, Pageable pageable);

    // 친한 친구 목록 조회
    FollowResponseDTO.FollowerListDTO getCircleFollowers(Member member, Long cursorId, Pageable pageable);

    // 타사용자의 팔로워 목록 조회
    FollowResponseDTO.FollowerListDTO getUserFollowers(Member member, String targetUsername, Long cursorId, Pageable pageable);

    // 타사용자의 팔로잉 목록 조회
    FollowResponseDTO.FollowingListDTO getUserFollowings(Member member, String targetUsername, Long cursorId, Pageable pageable);

    // 피드 캐싱용: 팔로잉 ID 목록
    List<Long> getFollowingIds(Long memberId);

    // 피드 캐싱용: 서클(친한친구) 팔로잉 ID 목록
    List<Long> getCircleFollowingIds(Long memberId);
}
