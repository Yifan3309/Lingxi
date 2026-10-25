package org.example.web;

import org.example.config.AiProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 当前生效的模型配置（密钥脱敏），用于验证“仅改配置即可切换 provider”。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AiProperties props;

    public AdminController(AiProperties props) {
        this.props = props;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chat", chatConfig());
        out.put("embedding", embeddingConfig());
        return out;
    }

    private Map<String, Object> chatConfig() {
        AiProperties.Chat cfg = props.chat();
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("provider", cfg.provider());
        if ("ollama".equals(cfg.provider())) {
            chat.put("baseUrl", cfg.ollama().baseUrl());
            chat.put("model", cfg.ollama().chatModel());
        } else {
            chat.put("baseUrl", cfg.openaiCompatible().baseUrl());
            chat.put("model", cfg.openaiCompatible().chatModel());
            chat.put("apiKey", mask(cfg.openaiCompatible().apiKey()));
        }
        return chat;
    }

    private Map<String, Object> embeddingConfig() {
        AiProperties.Embedding cfg = props.embedding();
        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("provider", cfg.provider());
        switch (cfg.provider()) {
            case "ollama" -> {
                embedding.put("baseUrl", cfg.ollama().baseUrl());
                embedding.put("model", cfg.ollama().embeddingModel());
            }
            case "onnx" -> {
                embedding.put("modelPath", cfg.onnx().modelPath());
                embedding.put("poolingMode", cfg.onnx().poolingMode());
            }
            default -> {
                embedding.put("baseUrl", cfg.openaiCompatible().baseUrl());
                embedding.put("model", cfg.openaiCompatible().embeddingModel());
                embedding.put("apiKey", mask(cfg.openaiCompatible().apiKey()));
            }
        }
        return embedding;
    }

    private static String mask(String key) {
        if (key == null || key.length() < 8) {
            return "***";
        }
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }
}
