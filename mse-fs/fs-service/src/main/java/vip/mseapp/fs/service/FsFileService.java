package vip.mseapp.fs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.fs.api.FileAccessLevel;
import vip.mseapp.fs.config.FsStorageProperties;
import vip.mseapp.fs.dto.MultipartInitResult;
import vip.mseapp.fs.entity.FsFile;
import vip.mseapp.fs.mapper.FsFileMapper;
import vip.mseapp.fs.storage.FileStorage;
import vip.mseapp.fs.storage.MultipartSession;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文件存储服务：上传（物理对象 + 元数据落库）、分页检索、代理下载、删除、预签名直链、
 * MD5 秒传、图片缩略图、分片直传登记。
 *
 * <p>租户隔离双层：元数据行由租户插件自动过滤（跨租户 fileId 直接 404）；物理对象键
 * 首段为租户 ID（{@link FileStorage#buildKey}）。上传为「物理对象先落、元数据后插」，
 * 元数据插入失败会遗留孤儿物理对象（与 OaFileStorage 同款边界，见 README 已知边界）。
 */
@Slf4j
@Service
public class FsFileService {

    /** 缩略图宽度档位（请求宽度归一到最近档；派生对象按档位缓存，删除时按档位清理） */
    private static final int[] THUMBNAIL_WIDTHS = {128, 256, 512};
    /** 参与缩略的源图上限（ImageIO 全量解码防 OOM；更大的图不生成缩略图） */
    private static final long THUMBNAIL_MAX_SOURCE_BYTES = 20L * 1024 * 1024;
    /** JDK ImageIO 可解码的图片类型（svg/webp 等不支持，直接 400） */
    private static final Set<String> THUMBNAIL_SOURCE_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif", "image/bmp");

    private final FsFileMapper fileMapper;
    private final FileStorage fileStorage;
    private final FsStorageProperties storageProperties;

    public FsFileService(FsFileMapper fileMapper, FileStorage fileStorage, FsStorageProperties storageProperties) {
        this.fileMapper = fileMapper;
        this.fileStorage = fileStorage;
        this.storageProperties = storageProperties;
    }

    // ==================== 上传 ====================

    /** 上传（HTTP multipart 入口；先算 MD5 查重，同租户同内容秒传复用物理对象）。 */
    public FsFile upload(MultipartFile file, String bizType, String bizId) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException("上传文件为空");
        }
        try {
            String md5 = digestHex(file.getInputStream());
            FsFile reusable = findReusable(md5, file.getSize());
            if (reusable != null) {
                return insertReusedMeta(file.getOriginalFilename(), reusable, bizType, bizId, null);
            }
            return upload(file.getOriginalFilename(), file.getContentType(),
                    file.getInputStream(), file.getSize(), bizType, bizId, null, md5);
        } catch (IOException e) {
            throw new ServiceException("读取上传内容失败：" + e.getMessage());
        }
    }

    /** 上传（通用流式入口：内容只消费一次，MD5 随物理对象写入同步计算；HTTP 与 RPC 契约共用）。 */
    public FsFile upload(String fileName, String contentType, InputStream in, long size,
                         String bizType, String bizId, String accessLevel) {
        return upload(fileName, contentType, in, size, bizType, bizId, accessLevel, null);
    }

    /** 上传（已知内容 MD5 时直接带入，跳过重复计算；md5 非空且命中复用对象即秒传）。 */
    public FsFile upload(String fileName, String contentType, InputStream in, long size,
                         String bizType, String bizId, String accessLevel, String contentMd5) {
        if (in == null || size <= 0) {
            throw new ServiceException("上传文件为空");
        }
        if (contentMd5 != null) {
            FsFile reusable = findReusable(contentMd5, size);
            if (reusable != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 内容不再消费，关闭失败可忽略
                }
                return insertReusedMeta(fileName, reusable, bizType, bizId, accessLevel);
            }
        }
        String original = fileName == null || fileName.isBlank() ? "file" : fileName;
        String ext = FileStorage.sanitizeExt(original);
        Long tenantId = TenantContext.getRequiredTenantId();
        Long fileId = IdWorker.getId();
        try (InputStream content = in) {
            InputStream stored = content;
            MessageDigest digest = null;
            if (contentMd5 == null) {
                digest = md5Digest();
                stored = new DigestInputStream(content, digest);
            }
            String key = fileStorage.store(tenantId, fileId, ext, contentType, stored, size);
            FsFile meta = new FsFile();
            meta.setId(fileId);
            meta.setFileName(original);
            meta.setStorageKey(key);
            meta.setStorageType(fileStorage.type());
            meta.setContentType(contentType);
            meta.setFileSize(size);
            meta.setBizType(bizType);
            meta.setBizId(bizId);
            meta.setAccessLevel(FileAccessLevel.PUBLIC.equals(accessLevel)
                    ? FileAccessLevel.PUBLIC : FileAccessLevel.TENANT);
            meta.setContentMd5(contentMd5 != null ? contentMd5 : hex(digest));
            fileMapper.insert(meta);
            return meta;
        } catch (IOException e) {
            throw new ServiceException("文件内容读取失败：" + e.getMessage());
        }
    }

    /** 同租户内按 MD5 查找可复用对象（分片进行中的行无完整对象，排除）。 */
    private FsFile findReusable(String contentMd5, long size) {
        if (contentMd5 == null || contentMd5.isBlank() || size <= 0) {
            return null;
        }
        List<FsFile> hits = fileMapper.selectList(new LambdaQueryWrapper<FsFile>()
                .eq(FsFile::getContentMd5, contentMd5)
                .eq(FsFile::getFileSize, size)
                .isNull(FsFile::getUploadId)
                .orderByDesc(FsFile::getId));
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** 秒传：新插元数据行指向已有物理对象（键/类型/大小/MD5 沿用，业务归属用本次请求的）。 */
    private FsFile insertReusedMeta(String fileName, FsFile reusable, String bizType, String bizId, String accessLevel) {
        FsFile meta = new FsFile();
        meta.setId(IdWorker.getId());
        meta.setFileName(fileName == null || fileName.isBlank() ? reusable.getFileName() : fileName);
        meta.setStorageKey(reusable.getStorageKey());
        meta.setStorageType(reusable.getStorageType());
        meta.setContentType(reusable.getContentType());
        meta.setFileSize(reusable.getFileSize());
        meta.setBizType(bizType);
        meta.setBizId(bizId);
        meta.setAccessLevel(FileAccessLevel.PUBLIC.equals(accessLevel)
                ? FileAccessLevel.PUBLIC : FileAccessLevel.TENANT);
        meta.setContentMd5(reusable.getContentMd5());
        fileMapper.insert(meta);
        log.info("秒传命中：新元数据 {} 复用对象 {}", meta.getId(), reusable.getStorageKey());
        return meta;
    }

    // ==================== 检索 / 下载 ====================

    /** 分页检索（租户插件自动过滤；文件名模糊 + 业务分类 + 时间范围）。 */
    public PageResult<FsFile> page(long pageNum, long pageSize, String keyword, String bizType,
                                   LocalDateTime beginTime, LocalDateTime endTime) {
        LambdaQueryWrapper<FsFile> wrapper = new LambdaQueryWrapper<FsFile>()
                .like(keyword != null && !keyword.isBlank(), FsFile::getFileName, keyword)
                .eq(bizType != null && !bizType.isBlank(), FsFile::getBizType, bizType)
                .ge(beginTime != null, FsFile::getCreateTime, beginTime)
                .le(endTime != null, FsFile::getCreateTime, endTime)
                .orderByDesc(FsFile::getId);
        Page<FsFile> page = fileMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    /** 元数据（租户插件自动过滤：他租户 fileId 查不到即 404，无需显式比对）。 */
    public FsFile get(Long id) {
        FsFile meta = fileMapper.selectById(id);
        if (meta == null) {
            throw new ServiceException(404, "文件不存在");
        }
        return meta;
    }

    /** 文件内容字节（RPC 契约 downloadFile 用；租户插件自动限定本租户）。 */
    public byte[] readBytes(Long id) {
        FsFile meta = get(id);
        try (InputStream in = fileStorage.open(meta.getStorageKey())) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new ServiceException("文件内容读取失败：" + e.getMessage());
        }
    }

    /**
     * 公开素材元数据（免登录）：按 URL 租户段（FsAssetUrls 规范）以 runAs 做语句级路由——
     * 精确复用上传时的落库决策（平台世界双库形态落平台库/微服务形态落主库，租户世界落
     * 共享主库或独立库），租户插件同时注入 {@code tenant_id = 租户段}：段与行不符即查不到，
     * 隔离性强于按 fileId 全库寻址；仅放行 access_level=public 的行（非公开与不存在同 404）。
     */
    public FsFile getPublicAsset(Long tenantId, Long id) {
        FsFile meta = TenantContext.runAs(tenantId, () -> fileMapper.selectById(id));
        if (meta == null || !FileAccessLevel.PUBLIC.equals(meta.getAccessLevel())) {
            // 非公开文件与不存在同语义 404，不泄露存在性
            throw new ServiceException(404, "素材不存在");
        }
        return meta;
    }

    /** 代理下载：流式透传（鉴权/租户校验/审计统一在本服务，两种存储模式行为一致）。 */
    public void download(Long id, HttpServletResponse response) throws IOException {
        FsFile meta = get(id);
        response.setContentType(meta.getContentType() == null ? "application/octet-stream" : meta.getContentType());
        if (meta.getFileSize() != null) {
            response.setContentLengthLong(meta.getFileSize());
        }
        String encoded = URLEncoder.encode(meta.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        try (InputStream in = fileStorage.open(meta.getStorageKey());
             OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    /**
     * 公开素材输出（免登录，/fs/asset/{tenantId}/{id}.{ext}）：inline 直显 + 内容定型 + 永久强缓存（fileId 内容不变），
     * 安全头与品牌素材本地存储同款（nosniff + CSP 禁脚本，SVG 直开不执行脚本）。
     */
    public void downloadPublicAsset(Long tenantId, Long id, HttpServletResponse response) throws IOException {
        FsFile meta = getPublicAsset(tenantId, id);
        response.setContentType(meta.getContentType() == null ? "application/octet-stream" : meta.getContentType());
        if (meta.getFileSize() != null) {
            response.setContentLengthLong(meta.getFileSize());
        }
        response.setHeader("Content-Disposition", "inline");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'none'");
        response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        try (InputStream in = fileStorage.open(meta.getStorageKey());
             OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    // ==================== 缩略图 ====================

    /**
     * 图片缩略图（/fs/file/{id}/thumbnail）：宽度归一到档位（{@link #THUMBNAIL_WIDTHS}），
     * 产物按「{storageKey}.th{width}」派生键惰性缓存——首次生成后直接回源输出。
     * JPEG 白底输出，PNG/GIF/BMP 保透明输出 PNG；不支持类型/超限源图 400。
     */
    public void downloadThumbnail(Long id, Integer width, HttpServletResponse response) throws IOException {
        int target = normalizeWidth(width);
        FsFile meta = get(id);
        String sourceType = meta.getContentType() == null ? "" : meta.getContentType().toLowerCase(Locale.ROOT);
        if (!THUMBNAIL_SOURCE_TYPES.contains(sourceType)) {
            throw new ServiceException(400, "该文件类型不支持生成缩略图（仅 jpeg/png/gif/bmp）");
        }
        if (meta.getFileSize() != null && meta.getFileSize() > THUMBNAIL_MAX_SOURCE_BYTES) {
            throw new ServiceException(400, "图片超过 20MB，未生成缩略图");
        }
        boolean jpeg = "image/jpeg".equals(sourceType);
        String contentType = jpeg ? "image/jpeg" : "image/png";
        String thumbKey = thumbKey(meta.getStorageKey(), target);
        byte[] bytes = readThumbCached(thumbKey);
        if (bytes == null) {
            bytes = generateThumbnail(meta, target, jpeg, thumbKey);
        }
        response.setContentType(contentType);
        response.setContentLengthLong(bytes.length);
        response.setHeader("Content-Disposition", "inline");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'none'");
        response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
        }
    }

    /** 缩略对象缓存命中读取（未生成为 null；缓存读失败降级为重新生成）。 */
    private byte[] readThumbCached(String thumbKey) {
        try (InputStream in = fileStorage.open(thumbKey)) {
            return in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    /** 解码源图 → 等比缩放 → 编码，产物尽力写入缓存（写失败仅告警，不阻塞输出）。 */
    private byte[] generateThumbnail(FsFile meta, int width, boolean jpeg, String thumbKey) throws IOException {
        BufferedImage source;
        try (InputStream in = fileStorage.open(meta.getStorageKey())) {
            source = ImageIO.read(in);
        }
        if (source == null) {
            throw new ServiceException(400, "图片解码失败，无法生成缩略图");
        }
        BufferedImage thumb = scale(source, width, jpeg);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(thumb, jpeg ? "jpg" : "png", buffer);
        byte[] bytes = buffer.toByteArray();
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            fileStorage.storeAs(thumbKey, jpeg ? "image/jpeg" : "image/png", in, bytes.length);
        } catch (Exception e) {
            log.warn("缩略图缓存写入失败（{}）：{}", thumbKey, e.getMessage());
        }
        return bytes;
    }

    /** 等比缩放（小图不放大）；JPEG 无透明通道铺白底，其余保留透明。 */
    private BufferedImage scale(BufferedImage source, int targetWidth, boolean opaque) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        if (sourceWidth <= targetWidth) {
            return source;
        }
        int height = Math.max(1, (int) Math.round(sourceHeight * (targetWidth / (double) sourceWidth)));
        BufferedImage thumb = new BufferedImage(targetWidth, height,
                opaque ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = thumb.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (opaque) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, targetWidth, height);
            }
            graphics.drawImage(source, 0, 0, targetWidth, height, null);
        } finally {
            graphics.dispose();
        }
        return thumb;
    }

    /** 请求宽度归一到档位（上限 512），缓存键与清理按档位收敛。 */
    private int normalizeWidth(Integer width) {
        int requested = width == null ? 256 : width;
        for (int candidate : THUMBNAIL_WIDTHS) {
            if (requested <= candidate) {
                return candidate;
            }
        }
        return THUMBNAIL_WIDTHS[THUMBNAIL_WIDTHS.length - 1];
    }

    /** 缩略派生键：在原键后追加 .th{width}（原键完整保留，删除时按档位枚举清理）。 */
    private String thumbKey(String storageKey, int width) {
        return storageKey + ".th" + width;
    }

    // ==================== 分片直传 ====================

    /**
     * 发起分片直传（仅 rustfs 模式）：预生成 fileId 与存储键并落元数据（upload_id 标记进行中），
     * 返回全部分片的预签名 PUT URL——客户端直传对象存储，服务端只签发与登记。
     */
    public MultipartInitResult initMultipart(String fileName, String contentType, long size,
                                             String bizType, String bizId) {
        if (fileName == null || fileName.isBlank() || contentType == null || contentType.isBlank()) {
            throw new ServiceException(400, "fileName 与 contentType 不能为空");
        }
        if (size <= 0) {
            throw new ServiceException(400, "文件大小必须大于 0");
        }
        Long tenantId = TenantContext.getRequiredTenantId();
        Long fileId = IdWorker.getId();
        Duration ttl = Duration.ofSeconds(Math.max(storageProperties.getPresign().getTtlSeconds(), 300));
        MultipartSession session = fileStorage.initMultipart(tenantId, fileId,
                FileStorage.sanitizeExt(fileName), contentType, size, ttl);
        FsFile meta = new FsFile();
        meta.setId(fileId);
        meta.setFileName(fileName);
        meta.setStorageKey(session.storageKey());
        meta.setStorageType(fileStorage.type());
        meta.setContentType(contentType);
        meta.setFileSize(size);
        meta.setBizType(bizType);
        meta.setBizId(bizId);
        meta.setAccessLevel(FileAccessLevel.TENANT);
        meta.setUploadId(session.uploadId());
        fileMapper.insert(meta);
        return new MultipartInitResult(fileId, session.partSize(), session.parts().stream()
                .map(part -> new MultipartInitResult.PartUrl(part.partNumber(), part.url()))
                .toList());
    }

    /** 分片组装完成：凭 uploadId 组装对象并清空标记，文件转为可用态。 */
    public FsFile completeMultipart(Long id, Map<Integer, String> partEtags) {
        if (partEtags == null || partEtags.isEmpty()) {
            throw new ServiceException(400, "分片 ETag 不能为空");
        }
        FsFile meta = get(id);
        if (meta.getUploadId() == null || meta.getUploadId().isBlank()) {
            throw new ServiceException(400, "该文件没有进行中的分片会话");
        }
        fileStorage.completeMultipart(meta.getStorageKey(), meta.getUploadId(), partEtags);
        // updateById 忽略 null 字段，置空须显式 set
        fileMapper.update(null, new LambdaUpdateWrapper<FsFile>()
                .eq(FsFile::getId, id)
                .set(FsFile::getUploadId, null));
        meta.setUploadId(null);
        return meta;
    }

    /** 放弃分片直传：回滚存储侧已传分片并逻辑删元数据行。 */
    public void abortMultipart(Long id) {
        FsFile meta = get(id);
        if (meta.getUploadId() != null && !meta.getUploadId().isBlank()) {
            try {
                fileStorage.abortMultipart(meta.getStorageKey(), meta.getUploadId());
            } catch (Exception e) {
                log.warn("分片会话放弃失败（storageKey={}）：{}", meta.getStorageKey(), e.getMessage());
            }
        }
        fileMapper.deleteById(id);
    }

    // ==================== 删除 / 其他 ====================

    /**
     * 删除：先逻辑删元数据（列表即消失），再尽力删除物理对象——秒传共享的对象仍有其他元数据行
     * 引用时保留物理对象（引用按租户插件过滤，与物理对象归属一致）；分片会话进行中先放弃。
     */
    public void delete(Long id) {
        FsFile meta = get(id);
        if (meta.getUploadId() != null && !meta.getUploadId().isBlank()) {
            abortMultipart(id);
            return;
        }
        fileMapper.deleteById(id);
        Long references = fileMapper.selectCount(new LambdaQueryWrapper<FsFile>()
                .eq(FsFile::getStorageKey, meta.getStorageKey())
                .ne(FsFile::getId, id));
        if (references != null && references > 0) {
            log.info("物理对象 {} 仍有 {} 个元数据引用，保留", meta.getStorageKey(), references);
            return;
        }
        try {
            fileStorage.delete(meta.getStorageKey());
            for (int width : THUMBNAIL_WIDTHS) {
                fileStorage.delete(thumbKey(meta.getStorageKey(), width));
            }
        } catch (Exception e) {
            log.warn("物理对象删除失败（storageKey={}）：{}", meta.getStorageKey(), e.getMessage());
        }
    }

    /** 预签名直链：仅 rustfs 模式且 presign 开关开启时可用；按单键限时签发，泄露面限于单文件。 */
    public String presignUrl(Long id) {
        if (!storageProperties.getPresign().isEnabled()) {
            throw new ServiceException(400, "预签名直链未开启（mse.fs.storage.presign.enabled=false）");
        }
        FsFile meta = get(id);
        long ttl = storageProperties.getPresign().getTtlSeconds();
        return fileStorage.presign(meta.getStorageKey(), Duration.ofSeconds(ttl > 0 ? ttl : 3600));
    }

    /** 当前存储模式（供管理端展示与联调确认）。 */
    public Map<String, Object> storageInfo() {
        boolean rustfs = FileStorage.TYPE_RUSTFS.equals(fileStorage.type());
        return Map.of(
                "type", fileStorage.type(),
                "presignEnabled", rustfs && storageProperties.getPresign().isEnabled(),
                "multipartEnabled", rustfs);
    }

    // ==================== MD5 工具 ====================

    /** 流式计算内容 MD5（32 位小写 hex）。 */
    private String digestHex(InputStream in) throws IOException {
        MessageDigest digest = md5Digest();
        try (InputStream content = in) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = content.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest);
    }

    private static MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 MD5 实现", e);
        }
    }

    private static String hex(MessageDigest digest) {
        StringBuilder builder = new StringBuilder(32);
        for (byte b : digest.digest()) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }
}
