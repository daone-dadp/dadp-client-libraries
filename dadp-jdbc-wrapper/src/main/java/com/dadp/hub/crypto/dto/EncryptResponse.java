package com.dadp.hub.crypto.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 암호화 응답 DTO
 *
 * Wrapper 전용 - Lombok 없이 수동 getter/setter
 *
 * @author DADP Development Team
 * @version 5.5.5
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EncryptResponse {

    @JsonProperty("success")
    private Boolean success;

    @JsonProperty("message")
    private String message;

    @JsonProperty("encryptedData")
    private String encryptedData;

    @JsonProperty("algorithm")
    private String algorithm;

    @JsonProperty("keyVersion")
    private Integer keyVersion;

    @JsonProperty("processingTime")
    private Long processingTime;

    @JsonProperty("policyUid")
    private String policyUid;

    public EncryptResponse() {}

    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getEncryptedData() { return encryptedData; }
    public void setEncryptedData(String encryptedData) { this.encryptedData = encryptedData; }

    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }

    public Integer getKeyVersion() { return keyVersion; }
    public void setKeyVersion(Integer keyVersion) { this.keyVersion = keyVersion; }

    public Long getProcessingTime() { return processingTime; }
    public void setProcessingTime(Long processingTime) { this.processingTime = processingTime; }

    public String getPolicyUid() { return policyUid; }
    public void setPolicyUid(String policyUid) { this.policyUid = policyUid; }
}
