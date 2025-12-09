package com.dadp.hub.crypto.dto;

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
public class DecryptRequest {
    
    /**
     * 복호화할 암호화된 데이터
     */
    @JsonProperty("encryptedData")
    private String encryptedData;
    
    /**
     * 마스킹 정책명 (복호화 시 마스킹 적용을 위해 사용)
     */
    @JsonProperty("maskPolicyName")
    private String maskPolicyName;
    
    /**
     * 마스킹 정책 UID (복호화 시 마스킹 적용을 위해 사용)
     */
    @JsonProperty("maskPolicyUid")
    private String maskPolicyUid;
    
    /**
     * 통계 정보 포함 여부
     * 기본값: false
     */
    @JsonProperty("includeStats")
    private Boolean includeStats = false;
}
