package com.jingluo.paismart.service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.poi.util.StringUtil;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;
import com.jingluo.paismart.domain.response.EmbeddingEstimate;
import com.jingluo.paismart.domain.response.LiteParsePage;
import com.jingluo.paismart.handler.StreamingEstimateHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 15:36
 * @Desc: 文档解析服务：基于 Apache Tika 流式解析文档、调用外部 LiteParse 处理 PDF、按语义切分文本分块并估算 Embedding 用量
 */
@Slf4j
@Service
public class ParseService {

    @Autowired
    private UsageQuotaService usageQuotaService;

    @Autowired
    private StreamingEstimateHandler streamingEstimateHandler;

    /**
     * 解析前内存保护的堆使用率阈值，超过则触发 GC 复查并可能拒绝处理，默认 0.8
     */
    @Value("${file.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;

    /**
     * 向量化子分块的目标大小（字符数）
     */
    @Value("${file.parsing.chunk-size}")
    private int chunkSize;

    /**
     * 相邻分块间语义重叠的大小（字符数）
     */
    @Value("${file.parsing.overlap-size:100}")
    private int overlapSize = 100;

    /**
     * 分块合并的最小块大小阈值（字符数），低于该值的相邻分块会被合并
     */
    @Value("${file.parsing.min-chunk-size:100}")
    private int minChunkSize = 100;

    /**
     * 流式读取缓冲区大小（字节）
     */
    @Value("${file.parsing.buffer-size:8192}")
    private int bufferSize;

    /**
     * PDF 解析引擎，当前仅支持 liteparse
     */
    @Value("${file.parsing.pdf.engine:liteparse}")
    private String pdfParsingEngine;

    /**
     * LiteParse 命令行可执行文件路径
     */
    @Value("${file.parsing.liteparse.command:lit}")
    private String liteParseCommand;

    /**
     * 是否启用 LiteParse OCR 识别扫描页
     */
    @Value("${file.parsing.liteparse.ocr-enabled:true}")
    private boolean liteParseOcrEnabled;

    /**
     * OCR 识别语言（Tesseract 语言包，如 chi_sim+eng）
     */
    @Value("${file.parsing.liteparse.ocr-language:chi_sim+eng}")
    private String liteParseOcrLanguage;

    /**
     * 单次解析的最大页数限制
     */
    @Value("${file.parsing.liteparse.max-pages:1000}")
    private int liteParseMaxPages;

    /**
     * PDF 页面渲染 DPI
     */
    @Value("${file.parsing.liteparse.dpi:150}")
    private int liteParseDpi;

    /**
     * Tesseract tessdata 目录，配置后注入子进程 TESSDATA_PREFIX 环境变量
     */
    @Value("${file.parsing.liteparse.tessdata-path:}")
    private String liteParseTessdataPath;

    /**
     * LiteParse 并行工作进程数，0 表示使用引擎默认值
     */
    @Value("${file.parsing.liteparse.num-workers:0}")
    private int liteParseNumWorkers;

    /**
     * LiteParse 进程执行超时时间（秒）
     */
    @Value("${file.parsing.liteparse.timeout-seconds:300}")
    private long liteParseTimeoutSeconds;

    /**
     * 是否启用阿里云 OCR 回调服务（LiteParse 通过回调地址调用本服务完成 OCR）
     */
    @Value("${aliyun.ocr.enabled:false}")
    private boolean aliyunOcrEnabled;

    /**
     * 阿里云 OCR 回调接口的鉴权 Token
     */
    @Value("${aliyun.ocr.callback-token:}")
    private String aliyunOcrCallbackToken;

    /**
     * 应用端口，用于拼接 OCR 回调地址
     */
    @Value("${server.port:8081}")
    private int serverPort;

    /**
     * 应用 context-path，用于拼接 OCR 回调地址
     */
    @Value("${server.servlet.context-path:}")
    private String serverContextPath;

    /**
     * liteparse 引擎标识
     */
    private static final String PDF_PARSER_LITEPARSE = "liteparse";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 匹配 LiteParse 输出中的页脚行（如 No.1/10），文本清洗时剔除
     */
    private static final Pattern LITEPARSE_PAGE_FOOTER_PATTERN = Pattern.compile("(?i)^No\\.\\s*\\d+\\s*/\\s*\\d+%?$");

    /**
     * 估算文件的 Embedding 用量：PDF 文档走 LiteParse 按页估算，其余类型由 Tika 流式解析估算。解析前会进行内存阈值检查，防止大文件解析导致 OOM
     *
     * @param fileStream
     *            文件输入流
     * @return 预估 Token 数与分块数
     * @throws IOException
     *             读取文件或调用外部解析引擎失败
     * @throws TikaException
     *             文档解析失败
     */
    public EmbeddingEstimate estimateEmbeddingUsage(InputStream fileStream) throws IOException, TikaException {
        checkMemoryThreshold();

        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            if (isPdfDocument(bufferedStream)) {
                return estimatePdfEmbeddingUsage(bufferedStream);
            }

            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();

            parser.parse(bufferedStream, streamingEstimateHandler, metadata, context);

            return streamingEstimateHandler.snapshot();
        } catch (SAXException e) {
            throw new RuntimeException("文档 Embedding Token 估算失败", e);
        }
    }

    /**
     * 基于 LiteParse 的逐页解析结果估算 PDF 的 Embedding 用量，空白页跳过
     */
    private EmbeddingEstimate estimatePdfEmbeddingUsage(InputStream fileStream) throws IOException {
        List<LiteParsePage> pages = parsePdfWithLiteParse(fileStream);
        long estimatedTokens = 0L;
        int estimatedChunkCount = 0;

        for (LiteParsePage page : pages) {
            String pageText = page.getText();
            if (pageText == null || pageText.isBlank()) {
                continue;
            }

            List<String> childChunks = splitTextIntoChunksWithSemantics(pageText, chunkSize);
            estimatedChunkCount += childChunks.size();
            estimatedTokens += usageQuotaService.estimateEmbeddingTokens(childChunks);
        }

        return new EmbeddingEstimate(estimatedTokens, estimatedChunkCount);
    }

    /**
     * 按语义切分文本：先按段落/句子切分为基础分块，再合并过小的分块， 最后为相邻分块添加语义重叠以保持上下文连续性
     *
     * @param text
     *            原始文本
     * @param chunkSize
     *            单个分块的目标大小（字符数）
     * @return 分块列表
     */
    public List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) {
        if (StringUtils.isBlank(text)) {
            return new ArrayList<>();
        }

        int effectiveChunkSize = Math.max(1, chunkSize);
        List<String> baseChunks = splitTextIntoBaseChunks(text, effectiveChunkSize);
        List<String> mergedChunks = mergeSmallChunks(baseChunks, effectiveChunkSize);

        return addSemanticOverlap(mergedChunks, effectiveChunkSize);
    }

    /**
     * 为相邻分块添加语义重叠：取前一分块结尾的完整句子拼接到下一分块开头， 提升向量检索时的上下文连续性
     */
    private List<String> addSemanticOverlap(List<String> chunks, int chunkSize) {
        int effectiveOverlapSize = normalizedOverlapSize(chunkSize);
        if (effectiveOverlapSize <= 0 || chunks.size() <= 1) {
            return chunks;
        }

        List<String> overlappedChunks = new ArrayList<>(chunks.size());
        overlappedChunks.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String overlapText = buildOverlapText(chunks.get(i - 1), effectiveOverlapSize);
            String currentChunk = chunks.get(i);
            if (overlapText.isEmpty()) {
                overlappedChunks.add(currentChunk);
            } else {
                overlappedChunks.add(overlapText + "\n\n" + currentChunk);
            }
        }

        return overlappedChunks;
    }

    /**
     * 从文本结尾向前选取不超过 maxLength 的完整句子作为重叠文本， 单句超长时按分词边界截取
     */
    private String buildOverlapText(String text, int maxLength) {
        if (StringUtils.isBlank(text) || maxLength <= 0) {
            return "";
        }

        List<String> sentences = splitIntoSentenceUnits(text);
        StringBuilder overlap = new StringBuilder();

        for (int i = sentences.size() - 1; i >= 0; i--) {
            String sentence = sentences.get(i).trim();
            if (sentence.isEmpty()) {
                continue;
            }

            if (sentence.length() > maxLength) {
                return overlap.isEmpty() ? tailByTokenBoundary(sentence, maxLength) : overlap.toString().trim();
            }

            if (overlap.length() + sentence.length() > maxLength) {
                break;
            }

            overlap.insert(0, sentence);
        }

        if (overlap.isEmpty()) {
            return tailByTokenBoundary(text, maxLength);
        }

        return overlap.toString().trim();
    }

    /**
     * 按 HanLP 分词边界从文本尾部截取不超过 maxLength 的内容， 分词失败时退化为按字符直接截取
     */
    private String tailByTokenBoundary(String text, int maxLength) {
        if (StringUtils.isBlank(text) || maxLength <= 0) {
            return "";
        }

        String normalized = text.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }

        try {
            List<Term> termList = StandardTokenizer.segment(normalized);
            StringBuilder tail = new StringBuilder();
            for (int i = termList.size() - 1; i >= 0; i--) {
                String word = termList.get(i).word;
                if (StringUtil.isBlank(word)) {
                    continue;
                }

                if (tail.length() + word.length() > maxLength) {
                    break;
                }

                tail.insert(0, word);
            }

            if (!tail.isEmpty()) {
                return tail.toString();
            }
        } catch (Exception e) {
            log.warn("HanLP overlap 边界处理失败，使用字符兜底: {}", e.getMessage());
        }

        return normalized.substring(Math.max(0, normalized.length() - maxLength));
    }

    /**
     * 将文本按中英文句末标点（。！？；.!?;）切分为句子单元列表
     */
    private List<String> splitIntoSentenceUnits(String text) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = Pattern.compile("[^。！？；.!?;]+[。！？；.!?;]?").matcher(text);
        while (matcher.find()) {
            String sentence = matcher.group().trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }

        if (sentences.isEmpty()) {
            sentences.add(text.trim());
        }

        return sentences;
    }

    /**
     * 合并小于最小块阈值的相邻分块，合并结果不超过单块上限， 避免产生大量碎片分块影响向量化效果
     */
    private List<String> mergeSmallChunks(List<String> chunks, int chunkSize) {
        List<String> merged = new ArrayList<>();
        int effectiveMinChunkSize = normalizedMinChunkSize(chunkSize);
        int maxMergedChunkSize = chunkSize + normalizedOverlapSize(chunkSize);

        for (String chunk : chunks) {
            String normalizedChunk = normalizeChunk(chunk);
            if (normalizedChunk.isEmpty()) {
                continue;
            }

            if (!merged.isEmpty()) {
                String previous = merged.get(merged.size() - 1);
                String combined = combineChunks(previous, normalizedChunk);
                if ((normalizedChunk.length() < effectiveMinChunkSize || previous.length() < effectiveMinChunkSize)
                    && combined.length() <= maxMergedChunkSize) {
                    merged.set(merged.size() - 1, combined);
                    continue;
                }
            }

            merged.add(normalizedChunk);
        }

        return merged;
    }

    /**
     * 用空行拼接两个分块，任一为空时返回另一个的规范化结果
     */
    private String combineChunks(String first, String second) {
        if (StringUtils.isBlank(first)) {
            return normalizeChunk(second);
        }

        if (StringUtils.isBlank(second)) {
            return normalizeChunk(first);
        }

        return normalizeChunk(first) + "\n\n" + normalizeChunk(second);
    }

    /**
     * 规范化分块文本：去除首尾空白，空文本返回空串
     */
    private String normalizeChunk(String chunk) {
        return StringUtils.isBlank(chunk) ? "" : chunk.trim();
    }

    /**
     * 计算有效的重叠大小：不超过单块上限，非法配置时返回 0（关闭重叠）
     */
    private int normalizedOverlapSize(int chunkSize) {
        if (overlapSize <= 0 || chunkSize <= 1) {
            return 0;
        }

        return Math.min(overlapSize, chunkSize - 1);
    }

    /**
     * 计算有效的最小块阈值：不超过单块上限
     */
    private int normalizedMinChunkSize(int chunkSize) {
        if (minChunkSize <= 0) {
            return 0;
        }

        return Math.min(minChunkSize, chunkSize);
    }

    /**
     * 基础切分：优先按段落聚合到目标大小，超长段落再按句子细分， 保证每个分块不超过 chunkSize
     */
    private List<String> splitTextIntoBaseChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        // 按段落分割
        String[] paragraphs = text.split("\n\n+");

        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (StringUtils.isBlank(paragraph)) {
                continue;
            }

            paragraph = paragraph.trim();

            // 如果单个段落超过chunk大小，需要进一步分割
            if (paragraph.length() > chunkSize) {
                // 先保存当前chunk
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }

                // 按句子分割长段落
                List<String> sentenceChunks = splitLongParagraph(paragraph, chunkSize);
                chunks.addAll(sentenceChunks);
            }
            // 如果添加这个段落会超过chunk大小
            else if (currentChunk.length() + paragraph.length() + paragraphSeparatorLength(currentChunk) > chunkSize) {
                // 保存当前chunk
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                }

                // 开始新chunk
                currentChunk = new StringBuilder(paragraph);
            }
            // 可以添加到当前chunk
            else {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n\n");
                }

                currentChunk.append(paragraph);
            }
        }

        // 添加最后一个chunk
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 当前块已有内容时段落间分隔符 "\n\n" 占用的长度
     */
    private int paragraphSeparatorLength(StringBuilder currentChunk) {
        return currentChunk.length() > 0 ? 2 : 0;
    }

    /**
     * 按句子切分超长段落，单句仍超限时继续按分词细分
     */
    private List<String> splitLongParagraph(String paragraph, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        // 按句子分割
        String[] sentences = paragraph.split("(?<=[。！？；])|(?<=[.!?;])\\s+");

        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }

                // 如果单个句子太长，按词分割
                if (sentence.length() > chunkSize) {
                    chunks.addAll(splitLongSentence(sentence, chunkSize));
                } else {
                    currentChunk.append(sentence);
                }
            } else {
                currentChunk.append(sentence);
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 使用 HanLP 分词将超长句子切分为不超过 chunkSize 的分块， 分词失败时按字符切分兜底
     */
    private List<String> splitLongSentence(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        try {
            // 使用HanLP StandardTokenizer进行分词
            List<Term> termList = StandardTokenizer.segment(sentence);

            StringBuilder currentChunk = new StringBuilder();
            for (Term term : termList) {
                String word = term.word;

                // 如果添加这个词会超过chunk大小限制，且当前chunk不为空
                if (currentChunk.length() + word.length() > chunkSize && !currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }

                currentChunk.append(word);
            }

            if (!currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
            }

        } catch (Exception e) {
            chunks = splitByCharacters(sentence, chunkSize);
        }

        return chunks;
    }

    /**
     * 按字符逐个切分文本，作为分词失败时的兜底策略
     */
    private List<String> splitByCharacters(String sentence, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);

            if (currentChunk.length() + 1 > chunkSize && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }

            currentChunk.append(c);
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    /**
     * 调用外部 LiteParse 进程解析 PDF：将文件流写入临时文件，执行命令行解析， 校验超时与退出码后读取 JSON 输出，无论成败均在 finally 中清理临时文件
     *
     * @param fileStream
     *            PDF 文件输入流
     * @return 按页组织的解析文本
     * @throws IOException
     *             启动进程失败、解析超时/失败或读取输出失败
     */
    private List<LiteParsePage> parsePdfWithLiteParse(InputStream fileStream) throws IOException {
        if (!PDF_PARSER_LITEPARSE.equalsIgnoreCase(pdfParsingEngine)) {
            throw new IOException("不支持的 PDF 解析引擎: " + pdfParsingEngine);
        }

        Path inputPath = Files.createTempFile("paismart-liteparse-", ".pdf");

        Path outputPath = Files.createTempFile("paismart-liteparse-", ".json");

        Path stdoutPath = Files.createTempFile("paismart-liteparse-", ".out");

        Path stderrPath = Files.createTempFile("paismart-liteparse-", ".err");

        try {
            Files.copy(fileStream, inputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            List<String> command = buildLiteParseCommand(inputPath, outputPath);

            Process process;
            try {
                ProcessBuilder processBuilder =
                    new ProcessBuilder(command).redirectOutput(stdoutPath.toFile()).redirectError(stderrPath.toFile());
                applyLiteParseEnvironment(processBuilder);
                process = processBuilder.start();
            } catch (IOException e) {
                throw new IOException(
                    "启动 LiteParse 失败，请确认已安装 lit 命令或配置 file.parsing.liteparse.command: " + liteParseCommand, e);
            }

            boolean finished;
            try {
                finished = process.waitFor(liteParseTimeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("等待 LiteParse 解析被中断", e);
            }

            if (!finished) {
                process.destroyForcibly();
                throw new IOException("LiteParse 解析超时: " + liteParseTimeoutSeconds + " 秒");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String stderr = readProcessLog(stderrPath);
                String stdout = readProcessLog(stdoutPath);
                throw new IOException(
                    "LiteParse 解析失败，exitCode=" + exitCode + ", stderr=" + stderr + ", stdout=" + stdout);
            }

            return readLiteParsePages(outputPath);
        } finally {
            deleteQuietly(inputPath);

            deleteQuietly(outputPath);

            deleteQuietly(stdoutPath);

            deleteQuietly(stderrPath);
        }
    }

    /**
     * 静默删除临时文件，失败仅记录告警不影响主流程
     */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除临时文件失败: {}", path, e);
        }
    }

    /**
     * 读取 LiteParse 输出的 JSON 结果：校验 pages 数组结构， 提取页码（缺省时按序号补齐）与文本并做规范化清洗
     */
    private List<LiteParsePage> readLiteParsePages(Path outputPath) throws IOException {
        JsonNode root = objectMapper.readTree(outputPath.toFile());
        JsonNode pagesNode = root.path("pages");

        if (!pagesNode.isArray()) {
            throw new IOException("LiteParse 输出缺少 pages 数组");
        }

        List<LiteParsePage> pages = new ArrayList<>();
        for (JsonNode pageNode : pagesNode) {
            int pageNumber = pageNode.path("page").asInt(0);
            if (pageNumber <= 0) {
                pageNumber = pages.size() + 1;
            }

            String pageText = pageNode.path("text").asText("");
            pages.add(new LiteParsePage(pageNumber, normalizeLiteParseText(pageText)));
        }

        return pages;
    }

    /**
     * 规范化单页文本：统一换行符与空格、剔除页脚行、压缩连续空行
     */
    private String normalizeLiteParseText(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }

        String[] lines = text.replace('\u00A0', ' ').replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        List<String> cleanedLines = new ArrayList<>(lines.length);
        boolean previousBlank = true;
        for (String line : lines) {
            String cleanedLine = normalizeLiteParseLine(line);
            if (cleanedLine.isBlank() || shouldSkipLiteParseLine(cleanedLine)) {
                if (!previousBlank) {
                    cleanedLines.add("");
                    previousBlank = true;
                }
                continue;
            }

            cleanedLines.add(cleanedLine);
            previousBlank = false;
        }

        return String.join("\n", cleanedLines).replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * 判断该行是否为应剔除的页脚行（如 No.3/10）
     */
    private boolean shouldSkipLiteParseLine(String line) {
        return LITEPARSE_PAGE_FOOTER_PATTERN.matcher(line).matches();
    }

    /**
     * 规范化单行文本：压缩多余空白，去除中文之间及中英文字符之间的冗余空格， 并修正标点符号前后的空格
     */
    private String normalizeLiteParseLine(String line) {
        if (StringUtils.isBlank(line)) {
            return "";
        }

        String cleaned = line.strip().replaceAll("[ \\t\\x0B\\f]+", " ");

        cleaned = cleaned.replaceAll("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})", "");
        cleaned = cleaned.replaceAll("(?<=[A-Za-z0-9])\\s+(?=\\p{IsHan})", "");
        cleaned = cleaned.replaceAll("(?<=\\p{IsHan})\\s+(?=[A-Za-z0-9])", "");
        cleaned = cleaned.replaceAll("\\s+([，。！？；：、）】》])", "$1");
        cleaned = cleaned.replaceAll("([，。！？；：、])\\s+(?=\\p{IsHan})", "$1");
        cleaned = cleaned.replaceAll("([（【《])\\s+", "$1");

        return cleaned.trim();
    }

    /**
     * 读取子进程 stdout/stderr 日志用于错误信息，超长时截断到 2000 字符
     */
    private String readProcessLog(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).trim();
            int maxLength = 2000;
            if (content.length() <= maxLength) {
                return content;
            }

            return content.substring(0, maxLength) + "...";
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 配置 LiteParse 子进程环境变量：指定 Tesseract tessdata 路径
     */
    private void applyLiteParseEnvironment(ProcessBuilder processBuilder) {
        if (hasText(liteParseTessdataPath)) {
            processBuilder.environment().put("TESSDATA_PREFIX", liteParseTessdataPath.trim());
        }
    }

    /**
     * 组装 LiteParse 命令行参数：JSON 输出格式、页数与 DPI 限制、 OCR 开关及识别语言、并行进程数、静默模式等
     */
    private List<String> buildLiteParseCommand(Path inputPath, Path outputPath) {
        List<String> command = new ArrayList<>();
        command.add(liteParseCommand);
        command.add("parse");
        command.add(inputPath.toString());
        command.add("--format");
        command.add("json");
        command.add("--output");
        command.add(outputPath.toString());
        command.add("--max-pages");
        command.add(String.valueOf(liteParseMaxPages));
        command.add("--dpi");
        command.add(String.valueOf(liteParseDpi));

        if (!liteParseOcrEnabled) {
            command.add("--no-ocr");
        } else {
            command.add("--ocr-language");
            command.add(liteParseOcrLanguage);
            String ocrServerUrl = effectiveLiteParseOcrServerUrl();
            if (hasText(ocrServerUrl)) {
                command.add("--ocr-server-url");
                command.add(ocrServerUrl);
            }
        }

        if (liteParseNumWorkers > 0) {
            command.add("--num-workers");
            command.add(String.valueOf(liteParseNumWorkers));
        }

        command.add("--quiet");

        return command;
    }

    /**
     * 拼接本服务对外提供的阿里云 OCR 回调地址（含鉴权 Token）， 供 LiteParse 在扫描页 OCR 时回调；未启用阿里云 OCR 时返回空串
     */
    private String effectiveLiteParseOcrServerUrl() {
        if (!aliyunOcrEnabled) {
            return "";
        }

        String contextPath = normalizeContextPath(serverContextPath);
        String url = "http://127.0.0.1:" + serverPort + contextPath + "/api/v1/internal/ocr/liteparse";
        if (hasText(aliyunOcrCallbackToken)) {
            url += "?token=" + URLEncoder.encode(aliyunOcrCallbackToken.trim(), StandardCharsets.UTF_8);
        }

        return url;
    }

    /**
     * 规范化 context-path：返回空串或以 / 开头且不以 / 结尾的路径，用于拼接回调地址
     */
    private String normalizeContextPath(String contextPath) {
        if (!hasText(contextPath) || "/".equals(contextPath.trim())) {
            return "";
        }

        String normalized = contextPath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }

        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    /**
     * 判断字符串是否包含非空白内容
     */
    private boolean hasText(String value) {
        return StringUtils.isNotBlank(value) && !value.trim().isEmpty();
    }

    /**
     * 通过文件头魔数 %PDF- 判断输入流是否为 PDF 文档，判断后流位置复位不影响后续读取
     */
    private boolean isPdfDocument(BufferedInputStream stream) throws IOException {
        stream.mark(bufferSize);
        byte[] header = stream.readNBytes(5);
        stream.reset();

        return header.length == 5 && "%PDF-".equals(new String(header, StandardCharsets.US_ASCII));
    }

    /**
     * 解析前的内存保护检查：堆使用率超过阈值时先触发 GC 后复查， 仍超标则拒绝处理，避免大文件解析导致 OOM
     */
    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double memoryUsage = (double)usedMemory / maxMemory;

        if (memoryUsage > maxMemoryThreshold) {
            System.gc();

            // 重新检查
            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double)usedMemory / maxMemory;

            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("内存不足，无法处理大文件。当前使用率: " + String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }
}
