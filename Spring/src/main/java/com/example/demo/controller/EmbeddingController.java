package com.example.demo.controller;

import com.example.demo.service.EmbeddingService;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/embeddings")
@CrossOrigin(origins = "*")
public class EmbeddingController {

    @Autowired
    private EmbeddingService embeddingService;

    @PostMapping
    public ResponseEntity<String> createEmbedding(@RequestHeader("Authorization") String authorization,
                                                  @RequestBody String requestBody) {
        try {
            String result = embeddingService.processEmbeddingRequest(authorization, requestBody);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result);
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("error", "Internal server error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(error.toString());
        }
    }
}
