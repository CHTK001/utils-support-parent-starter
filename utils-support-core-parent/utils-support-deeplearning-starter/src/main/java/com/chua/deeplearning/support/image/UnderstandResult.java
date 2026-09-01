package com.chua.deeplearning.support.image;
import lombok.Getter;
@Getter
public class UnderstandResult {
    private final UnderstandTask task;
    private final String text;
    public UnderstandResult(UnderstandTask task, String text) {
        this.task = task;
        this.text = text != null ? text.trim() : "";
    }
    public boolean hasResult() { return text != null && !text.isBlank(); }
}