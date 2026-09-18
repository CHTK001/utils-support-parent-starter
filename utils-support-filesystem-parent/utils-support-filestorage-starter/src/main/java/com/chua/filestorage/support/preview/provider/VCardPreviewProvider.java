package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
* v卡片 (VCF) 联系人预览提供器。
* <p>SPI 类型：{@code preview-vcard}。解析 VCF 文件中的联系人信息。</p>
*
* @author CH
* @since 4.0.0.42
* @param bytes bytes
* @return human大小的结果
* @param content 内容
* @param ext ext
* @param mime mime
 */
@Spi("preview-vcard")
public class VCardPreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("vcf", "vcard"); // 支持exts

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        String vcf = new String(content, StandardCharsets.UTF_8);
        List<ContactInfo> contacts = parseVCard(vcf);
        String html = buildHtml(contacts, content.length);

        return PreviewResult.builder()
                .htmlContent(html)
                .build();
    /**
    * 解析v卡片。
    * @param vcf vcf
    * @return 解析v卡片的结果
    */
    }

    private List<ContactInfo> parseVCard(String vcf) {
        List<ContactInfo> contacts = new ArrayList<>();
        String[] lines = vcf.split("\r?\n");

        ContactInfo current = null;

        for (String line : lines) {
            if (line.equals("BEGIN:VCARD")) {
                current = new ContactInfo();
                continue;
            }

            if (line.equals("END:VCARD")) {
                if (current != null) {
                    contacts.add(current);
                }
                current = null;
                continue;
            }

            if (current != null) {
                if (line.startsWith("FN:")) {
                    current.fullName = line.substring(3);
                } else if (line.startsWith("N:")) {
                    String[] parts = line.substring(2).split(";");
                    if (parts.length >= 2) {
                        current.lastName = parts[0];
                        current.firstName = parts[1];
                    }
                } else if (line.startsWith("TEL")) {
                    String value = extractValue(line);
                    if (value != null) {
                        current.phones.add(value);
                    }
                } else if (line.startsWith("EMAIL")) {
                    String value = extractValue(line);
                    if (value != null) {
                        current.emails.add(value);
                    }
                } else if (line.startsWith("ORG:")) {
                    current.organization = line.substring(4).replace(";", " ").trim();
                } else if (line.startsWith("TITLE:")) {
                    current.title = line.substring(6);
                } else if (line.startsWith("ADR")) {
                    String value = extractValue(line);
                    if (value != null) {
                        current.address = value.replace(";", " ").trim();
                    }
                } else if (line.startsWith("URL:")) {
                    current.url = line.substring(4);
                } else if (line.startsWith("NOTE:")) {
                    current.note = line.substring(5);
                }
            }
        }

        return contacts;
    /**
    * extract值。
    * @param line 线
    * @return extract值的结果
    */
    }

    private String extractValue(String line) {
        int colonIdx = line.indexOf(':');
        if (colonIdx < 0) {
            return null;
        }
        return line.substring(colonIdx + 1).trim();
    /**
    * 构建html。
    * @param contacts contacts
    * @param fileSize 文件大小
    * @return 构建html的结果
    */
    }

    private String buildHtml(List<ContactInfo> contacts, long fileSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<style>");
        sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}");
        sb.append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}");
        sb.append("h1{margin:0 0 8px;font-size:20px;font-weight:600}");
        sb.append(".meta{color:#6b7280;font-size:13px}");
        sb.append(".contacts{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:24px;max-width:700px;margin:0 auto}");
        sb.append(".contact{padding:16px;margin-bottom:12px;background:#f9fafb;border-radius:8px;border-left:4px solid #8b5cf6}");
        sb.append(".contact:last-child{margin-bottom:0}");
        sb.append(".name{font-weight:600;font-size:16px;margin-bottom:8px}");
        sb.append(".org{color:#6b7280;font-size:13px;margin-bottom:8px}");
        sb.append(".field{font-size:13px;margin-bottom:4px}");
        sb.append(".field-label{color:#6b7280;width:60px;display:inline-block}");
        sb.append(".field-value{color:#374151}");
        sb.append(".empty{color:#6b7280;text-align:center;padding:40px}");
        sb.append("</style></head><body>");

        sb.append("<div class=\"header\">");
        sb.append("<h1>联系人预览</h1>");
        sb.append("<div class=\"meta\">文件大小: ").append(humanSize(fileSize)).append(" · 联系人: ").append(contacts.size()).append("</div>");
        sb.append("</div>");

        sb.append("<div class=\"contacts\">");

        if (contacts.isEmpty()) {
            sb.append("<div class=\"empty\">未找到联系人</div>");
        } else {
            for (ContactInfo contact : contacts) {
                String displayName = contact.fullName;
                if (displayName == null || displayName.isEmpty()) {
                    displayName = (contact.firstName != null ? contact.firstName : "") +
                                  (contact.lastName != null ? contact.lastName : "");
                }
                if (displayName == null || displayName.isEmpty()) {
                    displayName = "未知联系人";
                }

                sb.append("<div class=\"contact\">");
                sb.append("<div class=\"name\">").append(escapeHtml(displayName)).append("</div>");

                if (contact.organization != null && !contact.organization.isEmpty()) {
                    sb.append("<div class=\"org\">").append(escapeHtml(contact.organization));
                    if (contact.title != null && !contact.title.isEmpty()) {
                        sb.append(" · ").append(escapeHtml(contact.title));
                    }
                    sb.append("</div>");
                } else if (contact.title != null && !contact.title.isEmpty()) {
                    sb.append("<div class=\"org\">").append(escapeHtml(contact.title)).append("</div>");
                }

                for (String phone : contact.phones) {
                    sb.append("<div class=\"field\"><span class=\"field-label\">电话</span><span class=\"field-value\">").append(escapeHtml(phone)).append("</span></div>");
                }

                for (String email : contact.emails) {
                    sb.append("<div class=\"field\"><span class=\"field-label\">邮箱</span><span class=\"field-value\">").append(escapeHtml(email)).append("</span></div>");
                }

                if (contact.address != null && !contact.address.isEmpty()) {
                    sb.append("<div class=\"field\"><span class=\"field-label\">地址</span><span class=\"field-value\">").append(escapeHtml(contact.address)).append("</span></div>");
                }

                sb.append("</div>");
            }
        }

        sb.append("</div></body></html>");
        return sb.toString();
    /**
    * escapehtml。
    * @param text 文本
    * @return escapeHtml的结果
    * @author CH
    * @since 4.0.0
    * @param bytes bytes
    */
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static class ContactInfo {
        String fullName; // 完整名称
        String firstName; // 第一个名称
        String lastName; // 最后一个名称
        String organization; // 组织
        String title; // title
        String address; // 地址
        String url; // url
        String note; // 笔记
        List<String> phones = new ArrayList<>(); // phones
        List<String> emails = new ArrayList<>(); // emails
    }
}
