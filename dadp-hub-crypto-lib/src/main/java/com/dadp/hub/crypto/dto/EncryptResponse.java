package com.dadp.hub.crypto.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Hub 암호화 응답 DTO
 * 
 * Hub의 실제 EncryptResponse 구조에 맞춤:
 * - success: boolean
 * - message: String
 * - encryptedData: String (직접 필드)
 * - algorithm: String
 * - keyVersion: Integer
 * - processingTime: Long
 * - policyUid: String
 * 
 * @author DADP Development Team
 * @version 1.0.0
 * @since 2025-01-01
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EncryptResponse {
    
    /**
     * 성공 여부
     */
    @JsonProperty("success")
    private Boolean success;
    
    /**
     * 오류 메시지
     */
    @JsonProperty("message")
    private String message;
    
    /**
     * 암호화된 데이터 (직접 필드)
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
    private Integer keyVersion;
    
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
