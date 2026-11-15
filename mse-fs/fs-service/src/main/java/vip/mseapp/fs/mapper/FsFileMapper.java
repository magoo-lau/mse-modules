package vip.mseapp.fs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mseapp.fs.entity.FsFile;

/** 文件元数据 Mapper（无 XML，Lambda 查询；租户条件由插件自动改写）。 */
@Mapper
public interface FsFileMapper extends BaseMapper<FsFile> {
}
