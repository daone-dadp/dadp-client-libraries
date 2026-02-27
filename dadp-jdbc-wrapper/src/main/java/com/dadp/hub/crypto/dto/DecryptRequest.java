package com.dadp.hub.crypto.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 복호화 요청 DTO
 *
 * Wrapper 전용 - Lombok 없이 수동 getter/setter
 *
 * @author DADP Development Team
 * @version 5.5.5
 */
public class DecryptRequest {

    @JsonProperty("encryptedData")
    private String encryptedData;

    @JsonProperty("policyName")
    private String policyName;

    @JsonProperty("maskPolicyName")
    private String maskPolicyName;

    @JsonProperty("maskPolicyUid")
    private String maskPolicyUid;

    @JsonProperty("includeStats")
    private Boolean includeStats = false;

    public DecryptRequest() {}

    public String getEncryptedData() { return encryptedData; }
    public void setEncryptedData(String encryptedData) { this.encryptedData = encryptedData; }

    public String getPolicyName() { return policyName; }
    public void setPolicyName(String policyName) { this.policyName = policyName; }

    public String getMaskPolicyName() { return maskPolicyName; }
    public void setMaskPolicyName(String maskPolicyName) { this.maskPolicyName = maskPolicyName; }

    public String getMaskPolicyUid() { return maskPolicyUid; }
    public void setMaskPolicyUid(String maskPolicyUid) { this.maskPolicyUid = maskPolicyUid; }

    public Boolean getIncludeStats() { return includeStats; }
    public void setIncludeStats(Boolean includeStats) { this.includeStats = includeStats; }

    @Override
    public String toString() {
        return "DecryptRequest{encryptedData=" + (encryptedData != null ? encryptedData.substring(0, Math.min(20, encryptedData.length())) + "..." : "null") +
               ", policyName=" + policyName + ", maskPolicyName=" + maskPolicyName + "}";
    }
}
