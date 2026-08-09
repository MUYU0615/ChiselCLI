package com.chisel.mcp.protocol;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class McpInitializeRequest {
    public static final String PROTOCOL_VERSION = "2025-03-26";

    private McpInitializeRequest() {
    }

    public static ObjectNode toJson() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = root.putObject("capabilities");
        capabilities.putObject("tools");
        // 声明 client 支持 sampling：允许 server 反向请求 client 用其 LLM 生成回复
        capabilities.putObject("sampling");
        ObjectNode clientInfo = root.putObject("clientInfo");
        clientInfo.put("name", "chisel");
        clientInfo.put("version", "11.0.0");
        return root;
    }
}
