package com.coredisc.application.service.member;

import com.coredisc.application.service.device.DeviceCommandService;
import com.coredisc.common.apiPayload.status.ErrorStatus;
import com.coredisc.common.converter.ProfileImgConverter;
import com.coredisc.common.exception.handler.AuthHandler;
import com.coredisc.common.exception.handler.MemberHandler;
import com.coredisc.common.exception.handler.ProfileImgHandler;
import com.coredisc.common.util.RedisUtil;
import com.coredisc.domain.member.Member;
import com.coredisc.domain.member.MemberRepository;
import com.coredisc.domain.profileImg.ProfileImg;
import com.coredisc.domain.profileImg.ProfileImgRepository;
import com.coredisc.infrastructure.aws.s3.ImageStorageService;
import com.coredisc.presentation.dto.member.MemberRequestDTO;
import com.coredisc.presentation.dto.profileImg.ProfileImgResponseDTO;
import com.coredisc.security.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberCommandServiceImpl implements MemberCommandService {

    private final MemberRepository memberRepository;
    private final ProfileImgRepository profileImgRepository;
    private final PasswordEncoder passwordEncoder;
    private final RedisUtil redisUtil;
    private final JwtProvider jwtProvider;
    private final DeviceCommandService deviceCommandService;
    private final ImageStorageService amazonS3Manager;

    @Override
    public void resetPassword(MemberRequestDTO.ResetPasswordDTO request) {

        Member member = memberRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new AuthHandler(ErrorStatus.MEMBER_NOT_FOUND));

        if (!request.getNewPassword().equals(request.getPasswordCheck())) {
            throw new AuthHandler(ErrorStatus.PASSWORD_NOT_EQUAL);
        }

        member.encodePassword(passwordEncoder.encode(request.getNewPassword()));
        memberRepository.save(member);
    }

    @Override
    public boolean resetNicknameAndUsernameMyHome(String accessToken, Member member, String deviceToken,
                                                  MemberRequestDTO.MyHomeResetNicknameAndUsernameDTO request) {

        boolean isUsernameChanged = false;

        // 아이디 변경
        if(!member.getUsername().equals(request.getNewUsername())) {
            if(memberRepository.existsByUsername(request.getNewUsername())){
                throw new MemberHandler(ErrorStatus.USERNAME_ALREADY_EXISTS);
            }
            member.setUsername(request.getNewUsername());
            isUsernameChanged = true;
        }

        // 닉네임 변경
        if(!member.getNickname().equals(request.getNewNickname())) {
            if(memberRepository.existsByNickname(request.getNewNickname())) {
                throw new MemberHandler(ErrorStatus.NICKNAME_ALREADY_EXISTS);
            }
            member.setNickname(request.getNewNickname());
        }

        memberRepository.save(member);

        // username 변경시 기존 accessToken 블랙리스트 저장
        if (isUsernameChanged) {
            logoutMember(accessToken, deviceToken, request.getNewUsername());
        }

        return isUsernameChanged;
    }

    @Override
    public void resignMember(Member member) {

        // 사용자가 등록했던 디바이스 토큰들 삭제
        deviceCommandService.deleteDeviceToken(member);
        member.setStatus(Boolean.FALSE);
        memberRepository.save(member);
    }

    @Override
    public void resetEmailMyHome(Member member, MemberRequestDTO.MyHomeResetEmailDTO request) {

        if(member.getEmail().equals(request.getEmail())) {
            throw new AuthHandler(ErrorStatus.SAME_EMAIL_REQUEST);
        }

        if(memberRepository.existsByEmail(request.getEmail())) {
            throw new AuthHandler(ErrorStatus.EMAIL_ALREADY_EXISTS);
        }

        member.setEmail(request.getEmail());
        memberRepository.save(member);
    }

    @Override
    public void resetPasswordMyHome(Member member, MemberRequestDTO.MyHomeResetPasswordDTO request) {

        if (!passwordEncoder.matches(request.getPassword(), member.getPassword())) {
            throw new AuthHandler(ErrorStatus.INVALID_CURRENT_PASSWORD);
        }
        if (!request.getNewPassword().equals(request.getPasswordCheck())) {
            throw new AuthHandler(ErrorStatus.PASSWORD_CHECK_NOT_EQUAL);
        }

        member.encodePassword(passwordEncoder.encode(request.getNewPassword()));
        memberRepository.save(member);
    }

    @Override
    public void resetUsernameMyHome(String accessToken, Member member, String deviceToken, MemberRequestDTO.MyHomeResetUsernameDTO request) {

        if(member.getUsername().equals(request.getNewUsername())) {
            throw new AuthHandler(ErrorStatus.SAME_USERNAME_REQUEST);
        }

        if(memberRepository.existsByUsername(request.getNewUsername())) {
            throw new AuthHandler(ErrorStatus.USERNAME_ALREADY_EXISTS);
        }

        member.setUsername(request.getNewUsername());
        memberRepository.save(member);

        logoutMember(accessToken, deviceToken, request.getNewUsername());
    }

    @Override
    public ProfileImgResponseDTO.ProfileImgDTO resetProfileImg(Member member, MultipartFile newProfileImg) {
        try {
            if (newProfileImg == null || newProfileImg.isEmpty()) {
                throw new ProfileImgHandler(ErrorStatus.FILE_NOT_FOUND);
            }

            // 1. 기존 이미지가 기본 프로필 이미지인지 판단
            ProfileImg defaultImg = profileImgRepository.findById(1L)
                    .orElseThrow(() -> new ProfileImgHandler(ErrorStatus.DEFAULT_PROFILE_IMG_NOT_FOUND));
            ProfileImg oldProfileImg = member.getProfileImg();

            // 2. 기존 프로필 이미지가 유저 개인 이미지라면 S3에서 삭제
            if (oldProfileImg != null && !oldProfileImg.getImgUrl().equals(defaultImg.getImgUrl())) {
                String oldUrl = oldProfileImg.getImgUrl();
                if (!oldUrl.isEmpty()) {
                    String oldFileKey = amazonS3Manager.extractFileKeyFromUrl(oldUrl);
                    if (oldFileKey != null) {
                        amazonS3Manager.deleteImageByKey("profiles/" + oldFileKey + ".jpg");
                    }
                }
            }

            // 3. 프로필 이미지 신규 업로드 및 저장
            amazonS3Manager.validateFile(newProfileImg);
            String fileKey = amazonS3Manager.generateFileKey(member.getId());
            String s3Key = "profiles/" + fileKey + ".jpg";
            String newUrl = amazonS3Manager.uploadToS3(newProfileImg, s3Key);

            oldProfileImg.setImgUrl(newUrl);
            profileImgRepository.save(oldProfileImg);

            return ProfileImgConverter.toProfileImgDTO(oldProfileImg);

        } catch (IOException e) {
            throw new ProfileImgHandler(ErrorStatus.FILE_UPLOAD_FAILED);
        } catch (Exception e) {
            throw new ProfileImgHandler(ErrorStatus.UNKNOWN_ERROR);
        }
    }

    @Override
    public ProfileImgResponseDTO.ProfileImgDTO resetToDefaultProfileImg(Member member) {

        // DB에 기본 프로필 이미지가 존재하지 않을 경우
        ProfileImg defualtProfileImg = profileImgRepository.findById(1L)
                .orElseThrow(() -> new ProfileImgHandler(ErrorStatus.DEFAULT_PROFILE_IMG_NOT_FOUND));

        // 사용자 현재 프로필 이미지
        ProfileImg oldProfileImg = member.getProfileImg();

        // 사용자가 이미 기본 프로필 이미지일 경우
        if(oldProfileImg.getImgUrl().equals(defualtProfileImg.getImgUrl())) {
            throw new ProfileImgHandler(ErrorStatus.PROFILE_IMG_ALREADY_DEFAULT);
        }

        oldProfileImg.setImgUrl(defualtProfileImg.getImgUrl());
        profileImgRepository.save(oldProfileImg);

        return ProfileImgConverter.toProfileImgDTO(oldProfileImg);
    }


    private void logoutMember(String accessToken, String deviceToken, String newUsername) {

        if(accessToken.startsWith("Bear ")) {
            accessToken = accessToken.substring(7);
        }
        // 디바이스 토큰 비활성화
        deviceCommandService.deactivateDeviceToken(newUsername, deviceToken);

        redisUtil.set(accessToken, "logout");
        redisUtil.expire(accessToken, jwtProvider.getRemainingExpiration(accessToken), TimeUnit.MILLISECONDS);
        // RefreshToken 삭제
        redisUtil.delete(jwtProvider.getUsername(accessToken));
    }
}
