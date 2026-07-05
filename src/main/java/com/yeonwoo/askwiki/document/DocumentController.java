package com.yeonwoo.askwiki.document;

import com.yeonwoo.askwiki.common.CreateDocumentRequest;
import com.yeonwoo.askwiki.common.CreateDocumentResponse;
import com.yeonwoo.askwiki.common.DocumentSummary;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final UploadedFileParser fileParser;

    public DocumentController(DocumentService documentService, UploadedFileParser fileParser) {
        this.documentService = documentService;
        this.fileParser = fileParser;
    }

    @PostMapping
    public CreateDocumentResponse create(@Valid @RequestBody CreateDocumentRequest request) {
        return documentService.create(request);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CreateDocumentResponse upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title
    ) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("업로드할 파일이 비어 있습니다.");
        }
        String content = fileParser.parse(file);
        if (content.isBlank()) {
            throw new IllegalArgumentException("파일에서 텍스트를 추출하지 못했습니다: " + file.getOriginalFilename());
        }
        String resolvedTitle = title == null || title.isBlank() ? fileParser.titleOf(file) : title;
        return documentService.create(new CreateDocumentRequest(resolvedTitle, content));
    }

    @GetMapping
    public List<DocumentSummary> list() {
        return documentService.list();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
