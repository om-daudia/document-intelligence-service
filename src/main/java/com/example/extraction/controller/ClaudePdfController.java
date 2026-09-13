package com.example.extraction.controller;

import com.example.extraction.service.ClaudePdfService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/pdf")
public class ClaudePdfController {

    private final ClaudePdfService service;

    public ClaudePdfController(ClaudePdfService service) { this.service = service; }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> analyze(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "prompt",
                    defaultValue = "Extract invoice number, date, vendor and total as JSON.")
            String prompt) throws Exception {

        if (file.isEmpty()) return ResponseEntity.badRequest().body("File is empty");
        return ResponseEntity.ok(service.analyze(file, prompt));
    }
}