package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class ContentFieldChange {
    @Column(name = "field_name", nullable = false, length = 50)
    private String fieldName;
    @Column(name = "before_value", columnDefinition = "text")
    private String beforeValue;
    @Column(name = "after_value", columnDefinition = "text")
    private String afterValue;

    protected ContentFieldChange() {}
    public ContentFieldChange(String fieldName, String beforeValue, String afterValue) {
        this.fieldName = DomainAssertions.requiredText(fieldName, "변경 항목", 50);
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
    }
    public String getFieldName() { return fieldName; }
    public String getBeforeValue() { return beforeValue; }
    public String getAfterValue() { return afterValue; }
}
