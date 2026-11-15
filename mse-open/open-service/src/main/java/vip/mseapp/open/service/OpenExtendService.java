package vip.mseapp.open.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mseapp.api.system.RemoteUserService;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.open.entity.OpenUserExtend;
import vip.mseapp.open.mapper.OpenUserExtendMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户扩展属性（JSON 整体读写，PUT 为全量覆盖语义）。
 *
 * <p>避免改动基座 sys_user 表结构；属性结构由接入系统自定（扁平对象，值不超过 4KB）。
 * 用户存在性校验经基座契约（RemoteUserService）调用 mse-system。
 */
@Service
public class OpenExtendService {

    /** 单用户扩展属性上限（序列化后字节数），防御大 JSON 拖垮行存储 */
    private static final int MAX_JSON_BYTES = 4096;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final OpenUserExtendMapper extendMapper;
    private final JsonMapper objectMapper;

    @RpcReference
    private RemoteUserService userService;

    public OpenExtendService(OpenUserExtendMapper extendMapper, JsonMapper objectMapper) {
        this.extendMapper = extendMapper;
        this.objectMapper = objectMapper;
    }

    /** 读取扩展属性；从未写入过返回空 Map */
    public Map<String, Object> get(Long userId) {
        requireUser(userId);
        OpenUserExtend extend = extendMapper.selectOne(new LambdaQueryWrapper<OpenUserExtend>()
                .eq(OpenUserExtend::getUserId, userId));
        if (extend == null || extend.getExtendJson() == null || extend.getExtendJson().isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(extend.getExtendJson(), MAP_TYPE);
        } catch (Exception e) {
            throw new ServiceException(500, "扩展属性数据损坏，无法解析");
        }
    }

    /** 全量覆盖写入（upsert） */
    @Transactional(rollbackFor = Exception.class)
    public void put(Long userId, Map<String, Object> attributes) {
        requireUser(userId);
        if (attributes == null) {
            attributes = new LinkedHashMap<>();
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(attributes);
        } catch (Exception e) {
            throw new ServiceException(400, "扩展属性序列化失败：" + e.getMessage());
        }
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new ServiceException(400, "扩展属性超过 " + MAX_JSON_BYTES + " 字节上限");
        }
        OpenUserExtend existing = extendMapper.selectOne(new LambdaQueryWrapper<OpenUserExtend>()
                .eq(OpenUserExtend::getUserId, userId));
        if (existing == null) {
            OpenUserExtend extend = new OpenUserExtend();
            extend.setUserId(userId);
            extend.setExtendJson(json);
            extendMapper.insert(extend);
        } else {
            existing.setExtendJson(json);
            extendMapper.updateById(existing);
        }
    }

    private void requireUser(Long userId) {
        if (userService.getUserById(userId) == null) {
            throw new ServiceException(404, "用户不存在: " + userId);
        }
    }
}
