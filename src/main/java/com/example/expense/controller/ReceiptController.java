package com.example.expense.controller;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import com.example.expense.dto.response.ReceiptResponse;
import com.example.expense.entity.Receipt;
import com.example.expense.exception.InvalidFileException;
import com.example.expense.service.ReceiptService;
import com.example.expense.web.CurrentUserProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/expenses/{expenseId}/receipts")
public class ReceiptController {

    private final ReceiptService receiptService;
    private final CurrentUserProvider currentUser;

    public ReceiptController(ReceiptService receiptService, CurrentUserProvider currentUser) {
        this.receiptService = receiptService;
        this.currentUser = currentUser;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReceiptResponse> upload(@PathVariable Long expenseId,
                                                  @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("Empty file");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new InvalidFileException("Could not read uploaded file");
        }
        Receipt receipt = receiptService.upload(currentUser.requireId(), expenseId, content,
                file.getSize(), file.getOriginalFilename());
        return ResponseEntity
                .created(URI.create("/api/expenses/" + expenseId + "/receipts/" + receipt.getId()))
                .body(ReceiptResponse.of(receipt));
    }

    @GetMapping
    public List<ReceiptResponse> list(@PathVariable Long expenseId) {
        return receiptService.list(currentUser.require(), expenseId).stream()
                .map(ReceiptResponse::of)
                .toList();
    }

    @GetMapping("/{receiptId}")
    public ReceiptResponse get(@PathVariable Long expenseId, @PathVariable Long receiptId) {
        ReceiptService.ReceiptView view =
                receiptService.getForDownload(currentUser.require(), expenseId, receiptId);
        return ReceiptResponse.withUrl(view.receipt(), view.url());
    }

    @DeleteMapping("/{receiptId}")
    public ResponseEntity<Void> delete(@PathVariable Long expenseId, @PathVariable Long receiptId) {
        receiptService.delete(currentUser.requireId(), expenseId, receiptId);
        return ResponseEntity.noContent().build();
    }
}
