package com.dadp.hub.crypto.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Hub 암호화 응답 DTO
 * 
 * @author DADP Development Team
 * @version 1.0.0
 * @since 2025-01-01
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EncryptResponse {
    
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
     * 암호화 데이터
     */
    @JsonProperty("data")
    private EncryptData data;
    
    /**
     * 암호화된 데이터 정보
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EncryptData {
        
        /**
         * 암호화된 데이터
         */
        @JsonProperty("encryptedData")
        private String encryptedData;
        
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
        
        /**
         * 정책 UID
         */
        @JsonProperty("policyUid")
        private String policyUid;
    }
}
