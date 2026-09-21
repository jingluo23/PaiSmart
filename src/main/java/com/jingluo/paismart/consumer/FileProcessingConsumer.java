package com.jingluo.paismart.consumer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.jingluo.paismart.domain.response.VectorizationUsageResult;
import com.jingluo.paismart.enums.FileProcessingTaskEnum;
import com.jingluo.paismart.model.FileProcessingTask;
import com.jingluo.paismart.service.DocumentService;
import com.jingluo.paismart.service.ParseService;
import com.jingluo.paismart.service.VectorizationService;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 15:14
 * @Desc: Kafka 文件处理任务消费者：消费文件处理主题中的任务消息， 异步执行文件下载、解析、向量化，并回写上传记录的向量化状态； 处理失败时抛出异常，交由 Kafka 错误处理器触发重试与死信
 */
@Service
@Slf4j
public class FileProcessingConsumer {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private ParseService parseService;

    @Autowired
    private VectorizationService vectorizationService;

    /**
     * 消费文件处理任务：上传处理任务执行"下载 → 解析 → 向量化"全流程； 重建索引任务委托给 reindexDocument 执行； 任一环节失败都会标记向量化失败并重新抛出异常，由 DefaultErrorHandler
     * 触发重试或进入死信队列
     *
     * @param task
     *            文件处理任务消息体
     */
    @KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}",
        groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        // 上传处理任务为首次向量化，无需重置历史用量（重建索引路径内部会传 true）
        documentService.markVectorizationProcessing(task.getFileMd5(), false);

        if (FileProcessingTaskEnum.TASK_TYPE_REINDEX.getTaskType().equals(task.getTaskType())) {
            processReindexTask(task);

            return;
        }

        InputStream fileStream = null;
        try {
            // 下载文件
            fileStream = downloadFileFromStorage(task.getFilePath());
            // 在 downloadFileFromStorage 返回后立即检查流是否可读
            if (fileStream == null) {
                throw new IOException("流为空");
            }

            // 强制转换为可缓存流
            if (!fileStream.markSupported()) {
                fileStream = new BufferedInputStream(fileStream);
            }

            // 解析文件
            parseService.parseAndSave(task.getFileMd5(), fileStream, task.getUserId(), task.getOrgTag(),
                task.isPublic());
            log.info("文件解析完成，fileMd5: {}", task.getFileMd5());

            // 向量化处理
            VectorizationUsageResult vectorizationResult = vectorizationService.vectorizeWithUsage(task.getFileMd5(),
                task.getUserId(), task.getOrgTag(), task.isPublic(), task.getUserId());

            documentService.markVectorizationCompleted(task.getFileMd5(), vectorizationResult);
        } catch (Exception e) {
            documentService.markVectorizationFailed(task.getFileMd5(), e);

            log.warn("处理任务时出错: {}", task, e);
            // 抛出异常让 Kafka 的 DefaultErrorHandler 捕获并触发重试 / 死信
            throw new RuntimeException("处理任务时出错", e);
        } finally {
            // 确保关闭输入流
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    log.warn("关闭文件流时出错", e);
                }
            }
        }
    }

    /**
     * 从存储下载文件并返回输入流：兼容本地文件系统路径与 http/https 远程地址（如对象存储预签名 URL， 连接超时 30 秒、读取超时 3 分钟）； 下载失败时记录日志并返回 null，由调用方统一转成异常进入重试流程
     *
     * @param filePath
     *            文件存储路径
     * @return 文件输入流，失败时返回 null
     */
    private InputStream downloadFileFromStorage(String filePath) {
        try {
            // 如果是文件系统路径
            File file = new File(filePath);
            if (file.exists()) {
                return new FileInputStream(file);
            }

            // 如果是远程 URL
            if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
                URL url = new URL(filePath);
                HttpURLConnection connection = (HttpURLConnection)url.openConnection();
                connection.setRequestMethod("GET");
                // 连接超时30秒
                connection.setConnectTimeout(30000);
                // 读取超时时间3分钟
                connection.setReadTimeout(180000);

                // 添加必要的请求头
                connection.setRequestProperty("User-Agent", "SmartPAI-FileProcessor/1.0");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    return connection.getInputStream();
                } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    throw new IOException("访问被禁止 - 预签名的URL可能已过期");
                } else {
                    throw new IOException(String.format("下载文件失败，HTTP 响应代码: %d", responseCode));
                }
            }

            // 如果既不是文件路径也不是 URL
            throw new IllegalArgumentException("不支持的文件路径格式: " + filePath);
        } catch (Exception e) {
            log.error("从存储下载文件时出错: {}", filePath, e);

            // 或者抛出异常
            return null;
        }
    }

    /**
     * 处理重建索引任务：向量化用量归属优先取任务中指定的发起人， 未指定时回退为文件上传用户
     *
     * @param task
     *            重建索引任务消息体
     */
    private void processReindexTask(FileProcessingTask task) {
        try {
            String requesterId = StringUtils.isBlank(task.getRequesterId()) ? task.getUserId() : task.getRequesterId();
            documentService.reindexDocument(task.getFileMd5(), requesterId);
        } catch (Exception e) {
            documentService.markVectorizationFailed(task.getFileMd5(), e);

            log.warn("任务重新索引时出错: {}", task, e);

            throw new RuntimeException("任务重新索引时出错", e);
        }
    }
}
