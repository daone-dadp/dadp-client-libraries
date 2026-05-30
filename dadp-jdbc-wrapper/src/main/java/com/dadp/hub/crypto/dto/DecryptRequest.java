package com.dadp.hub.crypto.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 복호화 요청 DTO
 *
 * Wrapper 전용 - Lombok 없이 수동 getter/setter
 *
 * @author DADP Development Team
 * @version 5.5.5
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DecryptRequest {

    @JsonProperty("data")
    private String data;

    /**
     * Legacy caller field. Engine 6.0 decrypt requests must not serialize policyName.
     */
    @JsonIgnore
    private String policyName;

    /**
     * Legacy caller field. Engine 6.0 decrypt requests must not serialize mask policy data.
     */
    @JsonIgnore
    private String maskPolicyName;

    /**
     * Legacy caller field. Engine 6.0 decrypt requests must not serialize mask policy data.
     */
    @JsonIgnore
    private String maskPolicyCode;

    public DecryptRequest() {}

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }

    @JsonIgnore
    public String getEncryptedData() { return data; }
    public void setEncryptedData(String encryptedData) { this.data = encryptedData; }

    public String getPolicyName() { return policyName; }
    public void setPolicyName(String policyName) { this.policyName = policyName; }

    public String getMaskPolicyName() { return maskPolicyName; }
    public void setMaskPolicyName(String maskPolicyName) { this.maskPolicyName = maskPolicyName; }

    public String getMaskPolicyCode() { return maskPolicyCode; }
    public void setMaskPolicyCode(String maskPolicyCode) { this.maskPolicyCode = maskPolicyCode; }

    @Override
    public String toString() {
        return "DecryptRequest{data=" + (data != null ? data.substring(0, Math.min(20, data.length())) + "..." : "null") +
               ", policyName=" + policyName + ", maskPolicyName=" + maskPolicyName + "}";
    }
}
