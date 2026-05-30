package com.dadp.hub.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 암호화 요청 DTO
 *
 * Wrapper 전용 - Lombok 없이 수동 getter/setter
 *
 * @author DADP Development Team
 * @version 5.5.5
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncryptRequest {

    @JsonProperty("data")
    private String data;

    @JsonProperty("policyName")
    private String policyName;

    public EncryptRequest() {}

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }

    public String getPolicyName() { return policyName; }
    public void setPolicyName(String policyName) { this.policyName = policyName; }

    @Override
    public String toString() {
        return "EncryptRequest{data=" + (data != null ? data.substring(0, Math.min(20, data.length())) + "..." : "null") +
               ", policyName=" + policyName + "}";
    }
}
