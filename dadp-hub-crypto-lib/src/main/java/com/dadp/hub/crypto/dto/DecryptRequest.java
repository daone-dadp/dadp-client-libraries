package com.dadp.hub.crypto.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Hub 복호화 요청 DTO
 * 
 * @author DADP Development Team
 * @version 1.0.0
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
}
