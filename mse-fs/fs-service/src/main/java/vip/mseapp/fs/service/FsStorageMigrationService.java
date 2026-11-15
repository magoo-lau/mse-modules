package vip.mseapp.fs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import vip.mseapp.api.tenant.RemoteTenantService;
import vip.mseapp.api.tenant.dto.TenantDTO;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.fs.config.FsStorageProperties;
import vip.mseapp.fs.entity.FsFile;
import vip.mseapp.fs.mapper.FsFileMapper;
import vip.mseapp.fs.storage.FileStorage;
import vip.mseapp.fs.storage.LocalFileStorage;
import vip.mseapp.fs.storage.RustFsFileStorage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 存储迁移工具（运维动作）：把历史元数据指向的物理对象从旧实现搬移到当前装配的实现
 * （目标 = 当前 {@code mse.fs.storage.type} 对应的 FileStorage Bean），成功后改写 storage_type。
 *
 * <p>源实现按类型动态构建（本地恒可用；rustfs 源要求 classpath 有 AWS SDK，fs-boot 已内置）。
 * <b>扫描面 = 全部世界</b>：fs_file 随租户路由分布在平台库、共享库与各独立租户专属库——
 * 租户会话以 {@code runAs(本租户)} 锚定本世界（独立库租户落专属库、共享租户落共享池带注入）；
 * 平台会话全量迁移按「平台库（0）→ 逐租户世界（api-tenant 清单，单库失败不拖垮整体）→
 * 内置共享库 runIgnore 兜底（无主/历史残留行）」三段扫描，逐段游标独立、幂等可重入。
 * 物理键本身已含租户前缀，逐键搬移不破坏隔离。分片进行中的行（upload_id 非空）无完整对象，跳过。
 */
@Slf4j
@Service
public class FsStorageMigrationService {

    private static final Set<String> STORAGE_TYPES = Set.of(FileStorage.TYPE_LOCAL, FileStorage.TYPE_RUSTFS);
    private static final long BATCH_SIZE = 200;

    private final FsFileMapper fileMapper;
    private final FileStorage targetStorage;
    private final FsStorageProperties properties;

    @RpcReference
    private RemoteTenantService remoteTenantService;

    /** 源实现缓存（一次迁移会话内复用；类型不匹配时重建） */
    private volatile FileStorage cachedSource;

    public FsStorageMigrationService(FsFileMapper fileMapper, FileStorage targetStorage,
                                     FsStorageProperties properties) {
        this.fileMapper = fileMapper;
        this.targetStorage = targetStorage;
        this.properties = properties;
    }

    /**
     * 把 storage_type=fromType 的历史对象搬移到当前存储实现。
     *
     * @param tenantId 迁移范围（null = 全部世界，平台会话运维；非空 = 仅该租户世界，租户会话域锁定）
     * @return from/to/migrated/failed/skipped/failedIds 统计
     */
    public Map<String, Object> migrate(String fromType, Long tenantId) {
        if (fromType == null || !STORAGE_TYPES.contains(fromType)) {
            throw new ServiceException(400, "from 取值须为 local / rustfs");
        }
        String targetType = targetStorage.type();
        if (fromType.equals(targetType)) {
            throw new ServiceException(400, "源存储与当前存储相同（当前 " + targetType + "），无需迁移");
        }
        FileStorage source = sourceStorage(fromType);
        if (FileStorage.TYPE_RUSTFS.equals(targetType) && targetStorage instanceof RustFsFileStorage rustFs) {
            rustFs.ensureBucket();
        }
        Stats stats = new Stats();
        if (tenantId != null) {
            TenantContext.runAs(tenantId, () -> doMigrate(fromType, source, tenantId, stats, "tenant-" + tenantId));
        } else {
            TenantContext.runAs(0L, () -> doMigrate(fromType, source, null, stats, "platform"));
            for (Long tid : listTenantIds()) {
                try {
                    TenantContext.runAs(tid, () -> doMigrate(fromType, source, tid, stats, "tenant-" + tid));
                } catch (Exception e) {
                    log.warn("存储迁移：租户 {} 世界扫描失败（专属库不可达？），跳过待下轮: {}", tid, e.getMessage());
                }
            }
            TenantContext.runIgnore(() -> doMigrate(fromType, source, null, stats, "shared-main"));
        }
        log.info("存储迁移完成：{} → {}，成功 {}，失败 {}，跳过 {}", fromType, targetType,
                stats.migrated, stats.failed, stats.skipped);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", fromType);
        result.put("to", targetType);
        result.put("migrated", stats.migrated);
        result.put("failed", stats.failed);
        result.put("skipped", stats.skipped);
        result.put("failedIds", stats.failedIds);
        return result;
    }

    /** 单世界游标扫描迁移（成败与否游标都前进，失败行不会造成死循环）；世界无存量时静默返回 */
    private void doMigrate(String fromType, FileStorage source, Long tenantId, Stats stats, String world) {
        String targetType = targetStorage.type();
        long migrated = 0;
        long failed = 0;
        long skipped = 0;
        long cursor = 0;
        while (true) {
            List<FsFile> batch = fileMapper.selectPage(new Page<>(1, BATCH_SIZE, false),
                    new LambdaQueryWrapper<FsFile>()
                            .eq(FsFile::getStorageType, fromType)
                            .eq(tenantId != null, FsFile::getTenantId, tenantId)
                            .gt(FsFile::getId, cursor)
                            .orderByAsc(FsFile::getId)).getRecords();
            if (batch.isEmpty()) {
                break;
            }
            for (FsFile meta : batch) {
                cursor = meta.getId();
                if (meta.getUploadId() != null && !meta.getUploadId().isBlank()) {
                    skipped++;
                    continue;
                }
                try {
                    copyObject(source, meta);
                    fileMapper.update(null, new LambdaUpdateWrapper<FsFile>()
                            .eq(FsFile::getId, meta.getId())
                            .set(FsFile::getStorageType, targetType));
                    migrated++;
                } catch (Exception e) {
                    failed++;
                    if (stats.failedIds.size() < 10) {
                        stats.failedIds.add(meta.getId());
                    }
                    log.warn("迁移失败（world={}，fileId={}，storageKey={}）：{}",
                            world, meta.getId(), meta.getStorageKey(), e.getMessage());
                }
            }
        }
        if (migrated > 0 || failed > 0 || skipped > 0) {
            log.info("存储迁移世界扫描 [{}]：成功 {}，失败 {}，跳过 {}", world, migrated, failed, skipped);
        }
        stats.migrated += migrated;
        stats.failed += failed;
        stats.skipped += skipped;
    }

    /** 租户清单（含停用；拉取失败降级为空——平台域与共享库兜底段仍执行） */
    private List<Long> listTenantIds() {
        try {
            if (remoteTenantService == null) {
                return List.of();
            }
            return remoteTenantService.listTenants().stream()
                    .map(TenantDTO::getId)
                    .filter(id -> id != null && id > 0)
                    .toList();
        } catch (Exception e) {
            log.warn("存储迁移：租户清单拉取失败，仅迁移平台域与共享库: {}", e.getMessage());
            return List.of();
        }
    }

    /** 跨世界累计统计 */
    private static final class Stats {

        private long migrated;
        private long failed;
        private long skipped;
        private final List<Long> failedIds = new ArrayList<>();
    }

    /** 按原键把对象从源存储搬移到目标存储（大小未知时读全字节兜底）。 */
    private void copyObject(FileStorage source, FsFile meta) throws Exception {
        long size = meta.getFileSize() == null ? 0 : meta.getFileSize();
        if (size > 0) {
            try (InputStream in = source.open(meta.getStorageKey())) {
                targetStorage.storeAs(meta.getStorageKey(), meta.getContentType(), in, size);
            }
            return;
        }
        byte[] content;
        try (InputStream in = source.open(meta.getStorageKey())) {
            content = in.readAllBytes();
        }
        try (InputStream in = new ByteArrayInputStream(content)) {
            targetStorage.storeAs(meta.getStorageKey(), meta.getContentType(), in, content.length);
        }
    }

    /** 按类型构建（或取缓存）源存储实现。 */
    private FileStorage sourceStorage(String fromType) {
        FileStorage cached = cachedSource;
        if (cached != null && cached.type().equals(fromType)) {
            return cached;
        }
        FileStorage created = buildSource(fromType);
        cachedSource = created;
        return created;
    }

    private FileStorage buildSource(String fromType) {
        if (FileStorage.TYPE_LOCAL.equals(fromType)) {
            return new LocalFileStorage(properties);
        }
        if (!ClassUtils.isPresent("software.amazon.awssdk.services.s3.S3Client", getClass().getClassLoader())) {
            throw new ServiceException(400, "源存储为 rustfs 但 classpath 缺少 AWS SDK（fs-boot 已内置；"
                    + "嵌入方请为本次迁移引入 software.amazon.awssdk:s3）");
        }
        RustFsFileStorage source = new RustFsFileStorage(properties);
        source.ensureBucket();
        return source;
    }
}
