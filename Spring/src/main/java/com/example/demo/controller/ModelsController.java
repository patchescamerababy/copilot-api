package com.example.demo.controller;

import com.example.demo.service.ModelsService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/models")
@CrossOrigin(origins = "*")
public class ModelsController {

    @Autowired
    private ModelsService modelsService;

    @GetMapping
    public ResponseEntity<String> getModels(@RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            JSONArray modelsArray = modelsService.getModels(authorization);
            JSONObject responseJson = new JSONObject();
            responseJson.put("data", modelsArray);
            responseJson.put("object", "list");
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(responseJson.toString());
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("error", "Internal server error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(error.toString());
        }
    }
}
