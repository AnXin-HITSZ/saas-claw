package com.anxin.pyclaw.backend.channel;

import com.anxin.pyclaw.backend.pyclaw.PyclawClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks")
public class ChannelWebhookProxyController {
    private final PyclawClient pyclawClient;

    public ChannelWebhookProxyController(PyclawClient pyclawClient) {
        this.pyclawClient = pyclawClient;
    }

    @GetMapping("/wechat")
    public ResponseEntity<byte[]> wechatGet(HttpServletRequest request) {
        return forward("wechat", request, new byte[0], "GET");
    }

    @PostMapping("/wechat")
    public ResponseEntity<byte[]> wechatPost(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        return forward("wechat", request, body, "POST");
    }

    @PostMapping("/feishu")
    public ResponseEntity<byte[]> feishuPost(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        return forward("feishu", request, body, "POST");
    }

    private ResponseEntity<byte[]> forward(String channel, HttpServletRequest request, byte[] body, String method) {
        return pyclawClient.forwardChannelWebhook(
                channel,
                request.getQueryString(),
                body == null ? new byte[0] : body,
                method,
                headers(request)
        );
    }

    private Map<String, List<String>> headers(HttpServletRequest request) {
        return Collections.list(request.getHeaderNames()).stream()
                .collect(Collectors.toMap(
                        name -> name,
                        name -> Collections.list(request.getHeaders(name))
                ));
    }
}
