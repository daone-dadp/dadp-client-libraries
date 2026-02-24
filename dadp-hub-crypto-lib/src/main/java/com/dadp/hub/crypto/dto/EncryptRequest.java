package com.dadp.hub.crypto.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Hub 암호화 요청 DTO
 * 
 * @author DADP Development Team
 * @version 1.0.0
 * @since 2025-01-01
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EncryptRequest {
    
    /**
     * 암호화할 데이터
     */
    @JsonProperty("data")
    private String data;
    
    /**
     * 암호화 정책명
     */
    @JsonProperty("policyName")
    private String policyName;
    
    /**
     * 정책 버전 (선택사항)
     */
    @JsonProperty("policyVersion")
    private String policyVersion;
    
    /**
     * 통계 정보 포함 여부
     * 기본값: false
     */
    @JsonProperty("includeStats")
    private Boolean includeStats = false;

    /**
     * 검색용 암호화 여부
     * true: Engine이 정책(useIv/usePlain)에 따라 암호화 또는 평문 반환
     */
    @JsonProperty("forSearch")
    private Boolean forSearch;
}
