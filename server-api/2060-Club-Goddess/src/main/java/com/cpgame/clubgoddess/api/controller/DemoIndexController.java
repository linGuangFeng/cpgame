package com.cpgame.clubgoddess.api.controller;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DemoIndexController {
    @Value("${demo.publish-directory:}") private String publishDirectory;
    @GetMapping(path="/", produces=MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> index() {
        Resource resource=new FileSystemResource(Path.of(publishDirectory).toAbsolutePath().normalize().resolve("index.html"));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(resource);
    }
}
