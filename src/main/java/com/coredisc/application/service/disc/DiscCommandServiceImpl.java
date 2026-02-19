package com.coredisc.application.service.disc;

import com.coredisc.common.apiPayload.status.ErrorStatus;
import com.coredisc.common.converter.DiscConverter;
import com.coredisc.common.exception.handler.DiscHandler;
import com.coredisc.domain.common.enums.DiscCoverColor;
import com.coredisc.domain.disc.Disc;
import com.coredisc.domain.disc.DiscRepository;
import com.coredisc.domain.member.Member;
import com.coredisc.infrastructure.aws.s3.ImageStorageService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@RequiredArgsConstructor
@Transactional
public class DiscCommandServiceImpl implements DiscCommandService {

    private final DiscRepository discRepository;
    private final ImageStorageService amazonS3Manager;

    @Override
    public Disc updateDiscCoverImage(Long discId, MultipartFile coverImageFile, Member member) {
        Disc disc = discRepository.findByIdAndMember(discId, member)
                .orElseThrow(() -> new DiscHandler(ErrorStatus.DISC_NOT_FOUND));

        String oldUrl = disc.getCoverImgUrl();

        try {
            // 기존에 설정한 이미지가 있으면 s3에서 삭제
            if (disc.hasCoverImage() && amazonS3Manager.isValidS3Url(oldUrl)) {
                String oldFileKey = amazonS3Manager.extractFileKeyFromUrl(oldUrl);
                if (oldFileKey != null) {
                    amazonS3Manager.deleteImageByKey("discCoverImages/" + oldFileKey + ".jpg");
                }
            }

            // 새로 저장
            amazonS3Manager.validateFile(coverImageFile);

            String fileKey = amazonS3Manager.generateFileKey(member.getId());
            String s3Key = "discCoverImages/" + fileKey + ".jpg";
            String newUrl = amazonS3Manager.uploadToS3(coverImageFile, s3Key);

            disc.setCoverImgUrl(newUrl);

        } catch (IOException e) {
            throw new DiscHandler(ErrorStatus.FILE_UPLOAD_FAILED);
        } catch (Exception e) {
        throw new DiscHandler(ErrorStatus.UNKNOWN_ERROR);
        }

        return discRepository.save(disc);
    }

    @Override
    public Disc updateDiscCoverColor(Long discId, DiscCoverColor coverColor, Member member) {
        Disc disc = discRepository.findByIdAndMember(discId, member)
                .orElseThrow(() -> new DiscHandler(ErrorStatus.DISC_NOT_FOUND));

        if (disc.getCoverImgUrl() != null){
            String oldUrl = disc.getCoverImgUrl();

            try {
                // S3에서 기존 이미지 삭제
                if (amazonS3Manager.isValidS3Url(oldUrl)) {
                    String oldFileKey = amazonS3Manager.extractFileKeyFromUrl(oldUrl);
                    if (oldFileKey != null) {
                        amazonS3Manager.deleteImageByKey("discCoverImages/" + oldFileKey + ".jpg");
                    }
                }
                disc.setCoverImgUrl(null);
                
            } catch (Exception e) {
                throw new DiscHandler(ErrorStatus.UNKNOWN_ERROR);
            }
        }

        disc.setCoverColor(coverColor);
        return discRepository.save(disc);
    }

    public boolean createDiscIfNotExists(Member member, int year, int month) {
        boolean exists = discRepository.existsByMemberAndYearAndMonth(member, year, month);
        if (!exists) {
            Disc disc = DiscConverter.toDisc(member, year, month);
            discRepository.save(disc);
            return true; // 생성됨
        }
        return false; // 이미 존재
    }
}