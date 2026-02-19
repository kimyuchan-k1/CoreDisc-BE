package com.coredisc.infrastructure.aws.s3;

import com.coredisc.common.apiPayload.status.ErrorStatus;
import com.coredisc.common.exception.handler.PostHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@Profile("local")
public class LocalImageStorageStub implements ImageStorageService {

    @Override
    public ImageUploadResult uploadFile(MultipartFile file, Long memberId) {
        validateFile(file);

        String fileKey = generateFileKey(memberId);
        String fakeOriginalUrl = "https://local-stub/original/" + fileKey + ".jpg";
        String fakeThumbnailUrl = "https://local-stub/thumbnail/" + fileKey + ".jpg";

        log.info("[STUB] 이미지 업로드 - 사용자: {}, 파일키: {}, 파일명: {}", memberId, fileKey, file.getOriginalFilename());

        return ImageUploadResult.builder()
                .originalUrl(fakeOriginalUrl)
                .thumbnailUrl(fakeThumbnailUrl)
                .originalKey("original/" + fileKey + ".jpg")
                .thumbnailKey("thumbnail/" + fileKey + ".jpg")
                .originalFileName(file.getOriginalFilename())
                .build();
    }

    @Override
    public void deleteImage(String key) {
        log.info("[STUB] 이미지 삭제 - 파일키: {}", key);
    }

    @Override
    public void deleteImageByUrl(String imageUrl) {
        log.info("[STUB] URL 기반 이미지 삭제 - URL: {}", imageUrl);
    }

    @Override
    public void deleteImageByKey(String s3Key) {
        log.info("[STUB] S3 키 기반 이미지 삭제 - 키: {}", s3Key);
    }

    @Override
    public String extractFileKeyFromUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return null;
        }
        String path = imageUrl.substring(imageUrl.lastIndexOf("/") + 1);
        if (path.endsWith(".jpg")) {
            path = path.substring(0, path.length() - 4);
        }
        log.info("[STUB] URL에서 파일키 추출 - URL: {}, 파일키: {}", imageUrl, path);
        return path;
    }

    @Override
    public boolean isValidS3Url(String url) {
        log.info("[STUB] S3 URL 유효성 검사 - URL: {}", url);
        return true;
    }

    @Override
    public void deleteImagesByUrls(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return;
        }
        log.info("[STUB] 다중 이미지 삭제 - 개수: {}", imageUrls.size());
    }

    @Override
    public void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new PostHandler(ErrorStatus.FILE_NOT_FOUND);
        }

        if (file.getSize() > 10 * 1024 * 1024) { // 10MB
            throw new PostHandler(ErrorStatus.FILE_SIZE_EXCEEDED);
        }

        String contentType = file.getContentType();

        if (contentType == null || !contentType.startsWith("image/")) {
            throw new PostHandler(ErrorStatus.INVALID_FILE_TYPE);
        }
    }

    @Override
    public String generateFileKey(Long memberId) {
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return String.format("user_%d_%s", memberId, uuid);
    }

    @Override
    public String uploadToS3(MultipartFile file, String s3Key) throws IOException {
        String fakeUrl = "https://local-stub/" + s3Key;
        log.info("[STUB] S3 업로드 - 키: {}, 파일명: {}", s3Key, file.getOriginalFilename());
        return fakeUrl;
    }
}
