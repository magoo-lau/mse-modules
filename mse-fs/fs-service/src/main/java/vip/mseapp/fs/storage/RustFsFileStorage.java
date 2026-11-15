package vip.mseapp.fs.storage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.fs.config.FsStorageProperties;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RustFS 1.x 对象存储（RustFS 完全兼容 S3 协议，官方文档指定经 AWS SDK for Java v2 接入；
 * 同样适用于 MinIO 等 S3 兼容存储）。
 *
 * <p>对象键 {tenantId}/{yyyyMM}/{fileId}.{ext}——首段租户前缀即桶内物理层隔离边界，
 * 预签名直链/分片直传均按单键签发（限时），不暴露桶内其他对象。
 */
public class RustFsFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(RustFsFileStorage.class);

    private final FsStorageProperties properties;
    private final String bucket;
    private final S3Client s3;

    /** 预签名器惰性构建（仅 presign/分片直传实际使用；S3Presigner 无线程安全问题） */
    private volatile S3Presigner presigner;

    public RustFsFileStorage(FsStorageProperties properties) {
        this.properties = properties;
        FsStorageProperties.Rustfs conf = properties.getRustfs();
        this.bucket = conf.getBucket();
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(conf.getEndpoint()))
                .region(Region.of(conf.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(conf.getAccessKey(), conf.getSecretKey())))
                .forcePathStyle(conf.isPathStyleAccess())
                // AWS SDK 新版默认对 PutObject 附带 CRC32 校验和扩展，S3 兼容存储普遍不认，
                // 置为按需启用以规避上传 400/501（见 rustfs/rustfs#1240 一类兼容性问题）
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    @PostConstruct
    public void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("RustFS 桶已创建: {}", bucket);
        } catch (RuntimeException e) {
            // 允许服务先启动（RustFS 未就绪/网络未通时不阻断）；首次上传时会暴露真实原因
            log.warn("RustFS 桶 [{}] 校验跳过：{}", bucket, e.getMessage());
        }
    }

    @Override
    public String store(Long tenantId, Long fileId, String ext, String contentType, InputStream in, long size) {
        return storeAs(FileStorage.buildKey(tenantId, fileId, ext), contentType, in, size);
    }

    @Override
    public String storeAs(String storageKey, String contentType, InputStream in, long size) {
        try {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .contentType(contentType)
                    .build(), RequestBody.fromInputStream(in, size));
        } catch (RuntimeException e) {
            throw new ServiceException("文件保存失败：" + e.getMessage());
        }
        return storageKey;
    }

    @Override
    public InputStream open(String storageKey) {
        try {
            return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (NoSuchKeyException e) {
            throw new ServiceException(404, "文件对象不存在");
        } catch (RuntimeException e) {
            throw new ServiceException("文件读取失败：" + e.getMessage());
        }
    }

    @Override
    public void delete(String storageKey) {
        // S3 语义：删除不存在的键同样返回成功，天然幂等
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (RuntimeException e) {
            throw new ServiceException("文件删除失败：" + e.getMessage());
        }
    }

    @Override
    public MultipartSession initMultipart(Long tenantId, Long fileId, String ext, String contentType,
                                          long size, Duration partUrlTtl) {
        String key = FileStorage.buildKey(tenantId, fileId, ext);
        String uploadId;
        try {
            uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).contentType(contentType).build()).uploadId();
        } catch (RuntimeException e) {
            throw new ServiceException("发起分片上传失败：" + e.getMessage());
        }
        long partSize = FileStorage.resolvePartSize(size);
        int partCount = (int) Math.max(1, (size + partSize - 1) / partSize);
        List<MultipartSession.PresignedPart> parts = new ArrayList<>(partCount);
        for (int partNumber = 1; partNumber <= partCount; partNumber++) {
            parts.add(new MultipartSession.PresignedPart(partNumber,
                    presignUploadPart(key, uploadId, partNumber, partUrlTtl)));
        }
        return new MultipartSession(key, uploadId, partSize, parts);
    }

    @Override
    public void completeMultipart(String storageKey, String uploadId, Map<Integer, String> partEtags) {
        List<CompletedPart> parts = partEtags.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> CompletedPart.builder().partNumber(e.getKey()).eTag(e.getValue()).build())
                .toList();
        try {
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(storageKey).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build());
        } catch (RuntimeException e) {
            throw new ServiceException("分片组装失败：" + e.getMessage());
        }
    }

    @Override
    public void abortMultipart(String storageKey, String uploadId) {
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket).key(storageKey).uploadId(uploadId).build());
        } catch (NoSuchUploadException e) {
            // 会话已不存在（已组装/已放弃）：按幂等成功处理
        } catch (RuntimeException e) {
            throw new ServiceException("放弃分片会话失败：" + e.getMessage());
        }
    }

    @Override
    public String presign(String storageKey, Duration ttl) {
        PresignedGetObjectRequest presigned = presigner().presignGetObject(b -> b
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(storageKey).build()));
        return presigned.url().toString();
    }

    private String presignUploadPart(String storageKey, String uploadId, int partNumber, Duration ttl) {
        PresignedUploadPartRequest presigned = presigner().presignUploadPart(b -> b
                .signatureDuration(ttl)
                .uploadPartRequest(UploadPartRequest.builder()
                        .bucket(bucket).key(storageKey).uploadId(uploadId).partNumber(partNumber).build()));
        return presigned.url().toString();
    }

    /** 惰性构建预签名器（直链/分片 URL 共用；配置在装配期已定，双重检查锁保证只建一次） */
    private S3Presigner presigner() {
        S3Presigner signer = this.presigner;
        if (signer == null) {
            synchronized (this) {
                if (this.presigner == null) {
                    FsStorageProperties.Rustfs conf = properties.getRustfs();
                    this.presigner = S3Presigner.builder()
                            .endpointOverride(URI.create(conf.getEndpoint()))
                            .region(Region.of(conf.getRegion()))
                            .credentialsProvider(StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create(conf.getAccessKey(), conf.getSecretKey())))
                            .serviceConfiguration(S3Configuration.builder()
                                    .pathStyleAccessEnabled(conf.isPathStyleAccess())
                                    .build())
                            .build();
                }
                signer = this.presigner;
            }
        }
        return signer;
    }

    @Override
    public String type() {
        return TYPE_RUSTFS;
    }

    @PreDestroy
    public void close() {
        s3.close();
        if (presigner != null) {
            presigner.close();
        }
    }
}
