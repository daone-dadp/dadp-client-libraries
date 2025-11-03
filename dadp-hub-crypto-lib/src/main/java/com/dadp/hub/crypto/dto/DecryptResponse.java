package com.dadp.hub.crypto.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Hub 복호화 응답 DTO
 * 
 * @author DADP Development Team
 * @version 1.0.0
 * @since 2025-01-01
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DecryptResponse {
    
    /**
     * 성공 여부
     */
    @JsonProperty("success")
    private boolean success;
    
    /**
     * 오류 메시지
     */
    @JsonProperty("message")
    private String message;
    
    /**
     * 복호화 데이터
     */
    @JsonProperty("data")
    private DecryptData data;
    
    /**
     * 복호화된 데이터 정보
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DecryptData {
        
        /**
         * 복호화된 데이터
         */
        @JsonProperty("decryptedData")
        private String decryptedData;
        
        /**
         * 사용된 알고리즘
         */
        @JsonProperty("algorithm")
        private String algorithm;
        
        /**
         * 키 버전
         */
        @JsonProperty("keyVersion")
        private String keyVersion;
        
        /**
         * 처리 시간 (ms)
         */
        @JsonProperty("processingTime")
        private Long processingTime;
    }
}
