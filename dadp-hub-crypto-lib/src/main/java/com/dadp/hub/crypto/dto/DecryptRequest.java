package com.dadp.hub.crypto.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Hub 복호화 요청 DTO
 * 
 * @author DADP Development Team
 * @version 2.0.0
 * @since 2025-01-01
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DecryptRequest {
    
    /**
     * 복호화할 암호화된 데이터
     */
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
    private String maskPolicyUid;

    @JsonIgnore
    public String getEncryptedData() {
        return data;
    }

    public void setEncryptedData(String encryptedData) {
        this.data = encryptedData;
    }
    
}
