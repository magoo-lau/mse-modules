package vip.mseapp.data.api.dto;

import java.io.Serializable;
import java.util.Map;

/**
 * 数据 API 调用请求。
 */
public class DataApiInvokeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数据 API 路径（data 服务「API 定义」中登记并已发布的 path） */
    private String apiPath;

    /** 调用参数（按 API 定义的参数名） */
    private Map<String, Object> params;

    /** 页码（传则分页查询） */
    private Integer pageNum;

    /** 页大小（传则分页查询） */
    private Integer pageSize;

    public String getApiPath() {
        return apiPath;
    }

    public void setApiPath(String apiPath) {
        this.apiPath = apiPath;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }
}
