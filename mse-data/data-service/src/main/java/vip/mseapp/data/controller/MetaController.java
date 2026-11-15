package vip.mseapp.data.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.alibaba.excel.EasyExcel;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;
import vip.mseapp.data.entity.DatMetaTable;
import vip.mseapp.data.service.MetaService;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 元数据与数据地图（租户端）。
 */
@Validated
@RestController
@RequestMapping("/data/meta")
public class MetaController {

    private final MetaService metaService;

    public MetaController(MetaService metaService) {
        this.metaService = metaService;
    }

    @SaCheckPermission(value = "data:meta:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/table/page")
    public Result<PageResult<DatMetaTable>> pageTables(@RequestParam(defaultValue = "1") long pageNum,
                                                       @RequestParam(defaultValue = "10") long pageSize,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String category,
                                                       @RequestParam(required = false) Long datasourceId) {
        return Result.ok(metaService.pageTables(pageNum, pageSize, keyword, category, datasourceId));
    }

    @SaCheckPermission(value = "data:meta:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/table/{id}")
    public Result<MetaService.TableDetail> tableDetail(@PathVariable Long id) {
        return Result.ok(metaService.tableDetail(id));
    }

    /** 采样预览（限行数） */
    @SaCheckPermission(value = "data:meta:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/table/{id}/sample")
    public Result<MetaService.SampleData> sample(@PathVariable Long id) {
        return Result.ok(metaService.sample(id));
    }

    /** 导出表数据为 xlsx（EasyExcel，内部 ods 表自动带租户条件，上限 5 万行） */
    @SaCheckPermission(value = "data:meta:export", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/table/{id}/export")
    public void export(@PathVariable Long id, @RequestParam(defaultValue = "10000") int limit,
                       HttpServletResponse response) throws IOException {
        MetaService.SampleData data = metaService.rows(id, limit);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode(data.columns().isEmpty() ? "export" : "data-export",
                StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename*=utf-8''" + fileName + "-" + id + ".xlsx");
        List<List<String>> head = data.columns().stream().map(List::of).toList();
        List<List<Object>> rows = data.rows().stream()
                .map(row -> data.columns().stream().map(row::get).collect(Collectors.toList()))
                .collect(Collectors.toList());
        EasyExcel.write(response.getOutputStream()).head(head).sheet("data").doWrite(rows);
    }

    /** 触发采集：datasourceId 为空时登记中台贴源层（ods_ 表）；为 0 时登记中台库业务表（同步取数正门） */
    @SaCheckPermission(value = "data:meta:collect", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/collect")
    public Result<Integer> collect(@RequestParam(required = false) Long datasourceId) {
        int count;
        if (datasourceId == null) {
            count = metaService.collectOds();
        } else if (datasourceId == 0L) {
            count = metaService.collectMasterSource();
        } else {
            count = metaService.collectFromDatasource(datasourceId);
        }
        return Result.ok(count);
    }
}
