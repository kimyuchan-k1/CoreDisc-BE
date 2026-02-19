package com.coredisc.infrastructure.aws.s3;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface ImageStorageService {

    ImageUploadResult uploadFile(MultipartFile file, Long memberId);

    void deleteImage(String key);

    void deleteImageByUrl(String imageUrl);

    void deleteImageByKey(String s3Key);

    String extractFileKeyFromUrl(String imageUrl);

    boolean isValidS3Url(String url);

    void deleteImagesByUrls(List<String> imageUrls);

    void validateFile(MultipartFile file);

    String generateFileKey(Long memberId);

    String uploadToS3(MultipartFile file, String s3Key) throws IOException;
}
