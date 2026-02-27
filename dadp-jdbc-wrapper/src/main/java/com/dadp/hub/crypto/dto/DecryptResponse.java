package com.dadp.hub.crypto.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 복호화 응답 DTO
 *
 * Wrapper 전용 - Lombok 없이 수동 getter/setter
 *
 * @author DADP Development Team
 * @version 5.5.5
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DecryptResponse {

    @JsonProperty("success")
    private Boolean success;

    @JsonProperty("message")
    private String message;

    @JsonProperty("decryptedData")
    private String decryptedData;

    @JsonProperty("algorithm")
    private String algorithm;

    @JsonProperty("keyVersion")
    private Integer keyVersion;

    @JsonProperty("processingTime")
    private Long processingTime;

    public DecryptResponse() {}

    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getDecryptedData() { return decryptedData; }
    public void setDecryptedData(String decryptedData) { this.decryptedData = decryptedData; }

    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }

    public Integer getKeyVersion() { return keyVersion; }
    public void setKeyVersion(Integer keyVersion) { this.keyVersion = keyVersion; }

    public Long getProcessingTime() { return processingTime; }
    public void setProcessingTime(Long processingTime) { this.processingTime = processingTime; }
}
