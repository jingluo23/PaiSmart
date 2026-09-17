package com.jingluo.paismart.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.jingluo.paismart.domain.response.ResponseResult;
import com.jingluo.paismart.service.ParseService;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 17:27
 * @Desc:
 */
@RestController
@RequestMapping("/api/v1/parse")
public class ParseController {

    @Autowired
    private ParseService parseService;

    @PostMapping
    public ResponseResult parseDocument(@RequestParam("file") MultipartFile file,
        @RequestParam("file_md5") String fileMd5, @RequestAttribute(value = "userId", required = false) String userId) {
        try {
            parseService.parseAndSave(fileMd5, file.getInputStream());

            return ResponseResult.success("文档解析成功");
        } catch (Exception e) {
            return ResponseResult.fail(HttpStatus.BAD_REQUEST.value(), "文档解析失败：" + e.getMessage());
        }
    }
}
