package vip.mseapp.im.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mseapp.im.entity.ImMsgAudit;

/**
 * IM 消息外发审计 Mapper。
 */
@Mapper
public interface ImMsgAuditMapper extends BaseMapper<ImMsgAudit> {
}
