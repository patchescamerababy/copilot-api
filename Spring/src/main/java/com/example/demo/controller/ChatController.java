package com.example.demo.controller;

import com.example.demo.service.CompletionService;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/chat/completions")
@CrossOrigin(origins = "*")
public class ChatController {

    @Autowired
    private CompletionService completionService;

    @GetMapping
    public ResponseEntity<String> welcome() {
        String response = "<html><head><title>Welcome to API</title></head>" +
                "<body>" +
                "<h1>Welcome to API</h1>" +
                "<p>This API is used to interact with the GitHub Copilot model. You can send messages to the model and receive responses.</p>" +
                "</body></html>";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        return new ResponseEntity<>(response, headers, HttpStatus.OK);
    }

    @PostMapping
    public ResponseEntity<String> createChatCompletion(@RequestHeader("Authorization") String authorization,
                                                       @RequestBody String requestBody) {
        try {
            String result = completionService.processRequest(authorization, requestBody);
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
