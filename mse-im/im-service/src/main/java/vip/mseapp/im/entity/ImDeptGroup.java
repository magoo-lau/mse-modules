package vip.mseapp.im.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 部门群映射（租户部门 ↔ OpenIM 部门群）：群主=部门负责人（未设置回落租户管理员），
 * 成员随部门名单对账收敛；部门删除时解散群并删映射行（部门编号雪花不复用，逻辑删无墓碑撞键风险）。
 */
@TableName("im_dept_group")
public class ImDeptGroup extends BaseEntity {

    /** 租户编号 */
    private Long tenantId;

    /** 部门编号（sys_dept.id） */
    private Long deptId;

    /** 部门名（建群时快照，改名随对账刷新） */
    private String deptName;

    /** OpenIM 群编号 */
    private String groupId;

    /** 群主（=部门负责人 sys_user.id，未设置时回落租户管理员） */
    private Long ownerUserId;

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getDeptId() {
        return deptId;
    }

    public void setDeptId(Long deptId) {
        this.deptId = deptId;
    }

    public String getDeptName() {
        return deptName;
    }

    public void setDeptName(String deptName) {
        this.deptName = deptName;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }
}
