package com.bidstay.hotel;

import com.bidstay.common.ApiException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
class DocumentFiles {
    private final S3Client s3;
    private final String bucket;
    private final Path localPath;
    private final boolean dev;

    DocumentFiles(Environment environment,
                  @Value("${bidstay.documents.bucket:}") String bucket,
                  @Value("${bidstay.documents.region:ap-south-1}") String region,
                  @Value("${bidstay.documents.local-path:.bidstay-documents}") String localPath) {
        this.bucket = bucket;
        this.localPath = Path.of(localPath);
        dev = environment.acceptsProfiles(Profiles.of("dev"));
        s3 = bucket.isBlank() ? null : S3Client.builder().region(Region.of(region)).build();
    }

    String upload(String key, String type, byte[] bytes) {
        try {
            if (s3 != null) {
                s3.putObject(request -> request.bucket(bucket).key(key).contentType(type)
                        .serverSideEncryption(ServerSideEncryption.AES256), RequestBody.fromBytes(bytes));
                return "S3";
            }
            if (dev) {
                Path target = localPath.resolve(key);
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
                return "LOCAL";
            }
            throw unavailable();
        } catch (IOException | SdkException failure) {
            throw unavailable();
        }
    }

    byte[] download(String provider, String key) {
        try {
            if ("S3".equals(provider) && s3 != null) {
                return s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asByteArray();
            }
            if ("LOCAL".equals(provider) && dev) return Files.readAllBytes(localPath.resolve(key));
            throw unavailable();
        } catch (IOException | SdkException failure) {
            throw unavailable();
        }
    }

    void remove(String provider, String key) {
        try {
            if ("S3".equals(provider) && s3 != null) s3.deleteObject(request -> request.bucket(bucket).key(key));
            if ("LOCAL".equals(provider) && dev) Files.deleteIfExists(localPath.resolve(key));
        } catch (IOException | SdkException ignored) {
            // The object stays private and can be removed by the storage lifecycle job.
        }
    }

    @PreDestroy
    void close() {
        if (s3 != null) s3.close();
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Document storage is unavailable");
    }
}
