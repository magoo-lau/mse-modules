package vip.mseapp.search.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

import java.time.LocalDateTime;

/**
 * 搜索热词（租户隔离表）：查询链路异步累加，供搜索框联想与运营分析。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("search_hot_keyword")
public class SearchHotKeyword extends BaseEntity {

    private String keyword;

    private Long hitCount;

    private LocalDateTime lastSearchTime;
}
