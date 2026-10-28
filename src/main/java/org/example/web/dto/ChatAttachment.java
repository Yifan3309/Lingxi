package org.example.web.dto;

/**
 * 问答附件（@链接）：仅本轮上下文，不入库、不写向量。
 * type: note（知识库笔记，id 必填）/ folder（文件夹，name 必填）/
 * localFile（本机路径，path 必填，仅 loopback）/ upload（已上传解析，uploadId 必填）。
 */
public record ChatAttachment(String type, String id, String name, String path, String uploadId) {
}
