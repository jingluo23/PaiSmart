package com.jingluo.paismart.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.jingluo.paismart.domain.response.FileTypeValidationResult;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 13:58
 * @Desc: 文件类型校验服务：基于扩展名白名单/黑名单校验上传文件是否为可解析、可向量化的文档类型
 */
@Service
public class FileTypeValidationService {

    /**
     * 校验文件类型是否支持解析与向量化：文件名为空、缺少扩展名、命中黑名单 或不在白名单内均视为校验失败，并给出对应提示
     *
     * @param fileName
     *            文件名（含扩展名）
     * @return 校验结果，包含是否通过、提示信息、类型描述与扩展名
     */
    public FileTypeValidationResult validateFileType(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return new FileTypeValidationResult(false, "文件名不能为空", "unknown", null);
        }

        // 提取文件扩展名
        String extension = extractFileExtension(fileName);
        if (StringUtils.isBlank(extension)) {
            return new FileTypeValidationResult(false, "文件必须有扩展名", "unknown", null);
        }

        String fileType = getFileTypeDescription(extension);

        // 检查是否为支持的文档类型
        if (SUPPORTED_DOCUMENT_EXTENSIONS.contains(extension)) {
            return new FileTypeValidationResult(true, "支持的文件类型", fileType, extension);
        }

        // 检查是否为明确不支持的类型
        if (UNSUPPORTED_EXTENSIONS.contains(extension)) {
            String message = String.format("不支持的文件类型：%s。系统仅支持文档类型文件的解析和向量化", fileType);

            return new FileTypeValidationResult(false, message, fileType, extension);
        }

        // 对于未知的文件类型，给出提示
        String message = String.format("未知的文件类型：%s。建议使用支持的文档格式（如PDF、Word、Excel、PowerPoint、文本文件等）", fileType);

        return new FileTypeValidationResult(false, message, fileType, extension);
    }

    /**
     * 明确不支持的上传扩展名黑名单：均为非文档类的媒体、二进制或系统文件
     */
    private static final Set<String> UNSUPPORTED_EXTENSIONS = new HashSet<>(Arrays.asList(
        // 图片文件
        "jpg", "jpeg", "png", "gif", "bmp", "svg", "webp", "tiff", "ico", "psd",

        // 音频文件
        "mp3", "wav", "flac", "aac", "ogg", "wma", "m4a",

        // 视频文件
        "mp4", "avi", "mov", "wmv", "flv", "mkv", "webm", "m4v", "3gp",

        // 压缩包
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz",

        // 可执行文件
        "exe", "msi", "dmg", "pkg", "deb", "rpm",

        // 字体文件
        "ttf", "otf", "woff", "woff2", "eot",

        // CAD文件
        "dwg", "dxf", "step", "iges",

        // 数据库文件
        "db", "sqlite", "mdb", "accdb",

        // 其他二进制文件
        "bin", "dat", "iso", "img"));

    /**
     * 支持解析与向量化的文档扩展名白名单
     */
    private static final Set<String> SUPPORTED_DOCUMENT_EXTENSIONS = new HashSet<>(Arrays.asList(
        // 文档类型
        "pdf", // PDF文档
        "doc", "docx", // Microsoft Word文档
        "xls", "xlsx", // Microsoft Excel表格
        "ppt", "pptx", // Microsoft PowerPoint演示文稿
        "txt", // 纯文本文件
        "rtf", // 富文本格式
        "md", // Markdown文档

        // OpenDocument格式
        "odt", // OpenDocument文本文档
        "ods", // OpenDocument电子表格
        "odp", // OpenDocument演示文稿

        // 网页和标记语言
        "html", "htm", // HTML文档
        "xml", // XML文档
        "json", // JSON文件
        "csv", // CSV文件

        // 电子书格式
        "epub", // EPUB电子书

        // 其他文档格式
        "pages", // Apple Pages文档
        "numbers", // Apple Numbers表格
        "keynote" // Apple Keynote演示文稿
    ));

    /**
     * 根据扩展名返回文件类型的中文描述，未识别的类型返回 "XX文件" 形式
     *
     * @param extension
     *            文件扩展名
     * @return 文件类型描述
     */
    private String getFileTypeDescription(String extension) {
        if (StringUtils.isBlank(extension)) {
            return "unknown";
        }

        // 根据文件扩展名返回文件类型
        switch (extension.toLowerCase()) {
            case "pdf":
                return "PDF文档";
            case "doc":
            case "docx":
                return "Word文档";
            case "xls":
            case "xlsx":
                return "Excel表格";
            case "ppt":
            case "pptx":
                return "PowerPoint演示文稿";
            case "txt":
                return "文本文件";
            case "rtf":
                return "富文本文档";
            case "md":
                return "Markdown文档";
            case "odt":
                return "OpenDocument文本";
            case "ods":
                return "OpenDocument表格";
            case "odp":
                return "OpenDocument演示文稿";
            case "html":
            case "htm":
                return "HTML文档";
            case "xml":
                return "XML文档";
            case "json":
                return "JSON文件";
            case "csv":
                return "CSV文件";
            case "epub":
                return "EPUB电子书";
            case "pages":
                return "Apple Pages文档";
            case "numbers":
                return "Apple Numbers表格";
            case "keynote":
                return "Apple Keynote演示文稿";
            case "jpg":
            case "jpeg":
                return "JPEG图片";
            case "png":
                return "PNG图片";
            case "gif":
                return "GIF图片";
            case "bmp":
                return "BMP图片";
            case "svg":
                return "SVG图片";
            case "mp4":
                return "MP4视频";
            case "avi":
                return "AVI视频";
            case "mov":
                return "MOV视频";
            case "mp3":
                return "MP3音频";
            case "wav":
                return "WAV音频";
            case "zip":
                return "ZIP压缩包";
            case "rar":
                return "RAR压缩包";
            case "7z":
                return "7Z压缩包";
            default:
                return extension.toUpperCase() + "文件";
        }
    }

    /**
     * 从文件名中提取小写扩展名
     *
     * @param fileName
     *            文件名
     * @return 小写扩展名，文件名为空或无扩展名时返回 null
     */
    private String extractFileExtension(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return null;
        }

        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return null;
        }

        return fileName.substring(lastDotIndex + 1).toLowerCase();
    }

    /**
     * 获取支持类型的中文描述集合（按类型去重）
     *
     * @return 文件类型描述集合
     */
    public Set<String> getSupportedFileTypes() {
        Set<String> supportedTypes = new HashSet<>();
        for (String extension : SUPPORTED_DOCUMENT_EXTENSIONS) {
            supportedTypes.add(getFileTypeDescription(extension));
        }

        return supportedTypes;
    }

    /**
     * 获取支持的上传扩展名集合（返回副本，避免外部修改白名单）
     *
     * @return 扩展名集合（小写）
     */
    public Set<String> getSupportedExtensions() {
        return new HashSet<>(SUPPORTED_DOCUMENT_EXTENSIONS);
    }
}
