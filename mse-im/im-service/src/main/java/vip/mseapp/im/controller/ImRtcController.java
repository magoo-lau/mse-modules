package vip.mseapp.im.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.result.Result;
import vip.mseapp.im.service.ImRtcService;

/**
 * 音视频通话接入端点（登录态）：签发 LiveKit 房间 token。
 * 信令本身走 OpenIM custom 在线消息（chat/call.ts 协议），这里只管媒体房间准入。
 */
@Validated
@RestController
@RequestMapping("/im/client/rtc")
public class ImRtcController {

    private final ImRtcService rtcService;

    public ImRtcController(ImRtcService rtcService) {
        this.rtcService = rtcService;
    }

    /** 签发 LiveKit token：room = 通话 callId（双端传同一房间名，各自以本人身份进房） */
    @PostMapping("/token")
    public Result<ImRtcService.TokenVO> token(@Valid @RequestBody TokenRequest request) {
        return Result.ok(rtcService.issueToken(request.room()));
    }

    public record TokenRequest(@NotBlank String room) {
    }
}
