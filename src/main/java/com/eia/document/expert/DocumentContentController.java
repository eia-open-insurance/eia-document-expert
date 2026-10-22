package com.eia.document.expert;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Raw content API for a document.
 *
 * <ul>
 *   <li>{@code PUT /api/v1/documents/{documentId}/content} appends a revision
 *       and makes it current. The body is raw bytes, {@code Content-Type} is
 *       the file MIME type, and {@code X-File-Name} is the URL-encoded
 *       filename. An empty body is {@code 400 EMPTY_CONTENT}; over 10 MiB is
 *       {@code 413 FILE_TOO_LARGE}.</li>
 *   <li>{@code DELETE .../content} tombstones the content: bytes are cleared
 *       for the current pointer and every revision and {@code deletedAt} is
 *       recorded, while the filename, MIME type, size, digest, and version stay
 *       readable. It answers {@code 204}; missing content is {@code 404
 *       CONTENT_NOT_FOUND}, a document under legal hold is {@code 409
 *       DOCUMENT_ON_LEGAL_HOLD}, and a repeated delete is {@code 409
 *       CONTENT_ALREADY_DELETED}.</li>
 *   <li>{@code GET .../content} returns the current bytes with the stored MIME
 *       type, an attachment disposition, {@code X-Content-SHA256}, and
 *       {@code X-Content-Version}. Tombstoned content is {@code 410
 *       CONTENT_DELETED}.</li>
 *   <li>{@code GET .../content/metadata} returns the current metadata without
 *       bytes, including {@code currentVersion}, nullable {@code deletedAt},
 *       and a derived {@code deleted} boolean; a document with no stored
 *       content is {@code 404 CONTENT_NOT_FOUND}. Metadata stays readable
 *       after a tombstone.</li>
 *   <li>{@code GET .../content/versions} lists revision metadata newest first
 *       as {@code {documentId, version, fileName, contentType, sizeBytes,
 *       sha256, uploadedAt}}.</li>
 *   <li>{@code GET .../content/versions/{version}} downloads one revision
 *       with the same headers as the current download. An unknown revision is
 *       {@code 404 CONTENT_VERSION_NOT_FOUND}; a tombstoned revision is
 *       {@code 410 CONTENT_DELETED}.</li>
 * </ul>
 *
 * <p>The tenant comes from {@code X-EIA-Tenant}; the internal {@code tenantId}
 * query parameter is accepted as a fallback. A missing tenant or a document
 * outside the tenant is {@code 404}, matching the rest of the public API.</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentContentController {
    private static final String TENANT_HEADER = "X-EIA-Tenant";
    private static final String FILE_NAME_HEADER = "X-File-Name";
    private static final String CONTENT_SHA256_HEADER = "X-Content-SHA256";
    private static final String CONTENT_VERSION_HEADER = "X-Content-Version";

    private final DocumentContentService service;

    public DocumentContentController(DocumentContentService service) {
        this.service = service;
    }

    @PutMapping("/{documentId}/content")
    public DocumentContentMetadata upload(
            @RequestHeader(name = TENANT_HEADER, required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID documentId,
            @RequestHeader(name = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestHeader(name = FILE_NAME_HEADER, required = false) String encodedFileName,
            @RequestBody(required = false) byte[] content) {
        UUID tenantId = TenantResolver.resolveForContent(headerTenant, queryTenant, documentId);
        String fileName = DocumentContentRules.decodeFileName(encodedFileName);
        return service.upload(tenantId, documentId, fileName, contentType, content);
    }

    @DeleteMapping("/{documentId}/content")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @RequestHeader(name = TENANT_HEADER, required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID documentId) {
        UUID tenantId = TenantResolver.resolveForContent(headerTenant, queryTenant, documentId);
        service.deleteContent(tenantId, documentId);
    }

    @GetMapping("/{documentId}/content")
    public ResponseEntity<byte[]> download(
            @RequestHeader(name = TENANT_HEADER, required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID documentId) {
        UUID tenantId = TenantResolver.resolveForContent(headerTenant, queryTenant, documentId);
        DocumentContent content = service.getContent(tenantId, documentId);
        return contentResponse(content.contentType(), content.fileName(), content.sizeBytes(),
                content.sha256(), content.currentVersion(), content.content());
    }

    @GetMapping("/{documentId}/content/metadata")
    public DocumentContentMetadata metadata(
            @RequestHeader(name = TENANT_HEADER, required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID documentId) {
        UUID tenantId = TenantResolver.resolveForContent(headerTenant, queryTenant, documentId);
        return service.getMetadata(tenantId, documentId);
    }

    @GetMapping("/{documentId}/content/versions")
    public List<DocumentContentVersionMetadata> versions(
            @RequestHeader(name = TENANT_HEADER, required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID documentId) {
        UUID tenantId = TenantResolver.resolveForContent(headerTenant, queryTenant, documentId);
        return service.listVersions(tenantId, documentId);
    }

    @GetMapping("/{documentId}/content/versions/{version}")
    public ResponseEntity<byte[]> downloadVersion(
            @RequestHeader(name = TENANT_HEADER, required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID documentId,
            @PathVariable int version) {
        UUID tenantId = TenantResolver.resolveForContent(headerTenant, queryTenant, documentId);
        DocumentContentVersion content = service.getVersionContent(tenantId, documentId, version);
        return contentResponse(content.contentType(), content.fileName(), content.sizeBytes(),
                content.sha256(), content.version(), content.content());
    }

    private static ResponseEntity<byte[]> contentResponse(String contentType, String fileName,
                                                          long sizeBytes, String sha256,
                                                          int version, byte[] content) {
        return ResponseEntity.ok()
                .contentType(mediaType(contentType))
                .contentLength(sizeBytes)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(fileName))
                .header(CONTENT_SHA256_HEADER, sha256)
                .header(CONTENT_VERSION_HEADER, Integer.toString(version))
                .body(content);
    }

    /**
     * Produces the conventional {@code attachment; filename="report.pdf"} for
     * an ASCII name and adds an RFC 5987 {@code filename*} parameter when the
     * name is not ASCII, so the downloaded file keeps its name without the
     * encoded-word form some clients do not decode.
     */
    private static String attachment(String fileName) {
        StringBuilder header = new StringBuilder("attachment; filename=\"")
                .append(quotedAscii(fileName))
                .append('"');
        if (!isAsciiPrintable(fileName)) {
            header.append("; filename*=UTF-8''").append(encodeExtended(fileName));
        }
        return header.toString();
    }

    private static String quotedAscii(String fileName) {
        StringBuilder out = new StringBuilder(fileName.length());
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c >= 0x20 && c <= 0x7e) {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.toString();
    }

    private static boolean isAsciiPrintable(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static String encodeExtended(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (byte valueByte : bytes) {
            int c = valueByte & 0xff;
            if (isAttrChar(c)) {
                out.append((char) c);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    private static boolean isAttrChar(int c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || "!#$&+-.^_`|~".indexOf(c) >= 0;
    }

    private static MediaType mediaType(String raw) {
        try {
            return MediaType.parseMediaType(raw);
        } catch (InvalidMediaTypeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
