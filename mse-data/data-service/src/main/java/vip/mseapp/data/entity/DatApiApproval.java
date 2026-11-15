package vip.mseapp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 数据服务 API 审批记录（提交/通过/驳回/下线的审计留痕）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dat_api_approval")
public class DatApiApproval extends BaseEntity {

    public static final String ACTION_SUBMIT = "submit";
    public static final String ACTION_APPROVE = "approve";
    public static final String ACTION_REJECT = "reject";
    public static final String ACTION_OFFLINE = "offline";

    /** API 编号 */
    private Long apiId;

    /** API 名称冗余 */
    private String apiName;

    /** 动作：submit / approve / reject / offline */
    private String action;

    /** 审批意见 */
    private String comment;

    /** 操作人编号 */
    private Long operatorId;

    /** 操作人姓名 */
    private String operatorName;
}
