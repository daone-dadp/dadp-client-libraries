package com.dadp.aop.service;

import com.dadp.hub.crypto.HubCryptoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * DADP AOP 암복호화 서비스 (Hub 암복호화 라이브러리 사용)
 * 
 * @author DADP Development Team
 * @version 2.0.0
 * @since 2025-01-01
 */
@Service
public class CryptoService {
    
    @Autowired
    private HubCryptoService hubCryptoService;
    
    /**
     * 데이터 암호화 (Hub 암복호화 라이브러리 사용)
     * 
     * @param data 암호화할 데이터
     * @param policy 암호화 정책명
     * @return 암호화된 데이터
     */
    public String encrypt(String data, String policy) {
        return hubCryptoService.encrypt(data, policy);
    }
    
    /**
     * 데이터 복호화 (Hub 암복호화 라이브러리 사용)
     * 
     * @param encryptedData 복호화할 암호화된 데이터
     * @return 복호화된 데이터
     */
    public String decrypt(String encryptedData) {
        return hubCryptoService.decrypt(encryptedData, null, null);
    }
    
    /**
     * 데이터 복호화 (Hub 암복호화 라이브러리 사용, 마스킹 정책 포함)
     * 
     * Proxy의 HubCryptoAdapter와 동일하게 처리:
     * - Hub에 복호화 요청 (암호화 여부 판단하지 않음)
     * - null 반환 시 "데이터가 암호화되지 않았습니다" 의미 → 원본 데이터 반환
     * 
     * @param encryptedData 복호화할 암호화된 데이터 (또는 일반 텍스트)
     * @param maskPolicyName 마스킹 정책명 (선택사항)
     * @param maskPolicyUid 마스킹 정책 UID (선택사항)
     * @return 복호화된 데이터 (null이면 원본 데이터 반환)
     */
    public String decrypt(String encryptedData, String maskPolicyName, String maskPolicyUid) {
        return decrypt(encryptedData, maskPolicyName, maskPolicyUid, false);
    }
    
    /**
     * 데이터 복호화 (Hub 암복호화 라이브러리 사용, 마스킹 정책 및 통계 정보 포함)
     * 
     * Proxy의 HubCryptoAdapter와 동일하게 처리:
     * - Hub에 복호화 요청 (암호화 여부 판단하지 않음)
     * - null 반환 시 "데이터가 암호화되지 않았습니다" 의미 → 원본 데이터 반환
     * 
     * @param encryptedData 복호화할 암호화된 데이터 (또는 일반 텍스트)
     * @param maskPolicyName 마스킹 정책명 (선택사항)
     * @param maskPolicyUid 마스킹 정책 UID (선택사항)
     * @param includeStats 통계 정보 포함 여부
     * @return 복호화된 데이터 (null이면 원본 데이터 반환)
     */
    public String decrypt(String encryptedData, String maskPolicyName, String maskPolicyUid, boolean includeStats) {
        if (encryptedData == null) {
            return null;
        }
        
        try {
            // Hub/Engine에서 암호화 여부 판단 및 처리
            String decrypted = hubCryptoService.decrypt(encryptedData, maskPolicyName, maskPolicyUid, includeStats);
            
            // null 반환 시 "데이터가 암호화되지 않았습니다" 의미 (원본 데이터 반환)
            if (decrypted == null) {
                return encryptedData;
            }
            
            return decrypted;
        } catch (Exception e) {
            // 예외 발생 시 원본 데이터 반환 (fail-open 모드)
            return encryptedData;
        }
    }
    
    /**
     * 배치 복호화 (Hub 암복호화 라이브러리 사용)
     * 
     * @param encryptedDataList 복호화할 암호화된 데이터 목록
     * @param maskPolicyName 마스킹 정책명 (선택사항)
     * @param maskPolicyUid 마스킹 정책 UID (선택사항)
     * @param includeStats 통계 정보 포함 여부
     * @return 복호화된 데이터 목록 (순서 보장)
     */
    public java.util.List<String> batchDecrypt(java.util.List<String> encryptedDataList, 
                                                String maskPolicyName, 
                                                String maskPolicyUid, 
                                                boolean includeStats) {
        if (encryptedDataList == null || encryptedDataList.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        
        try {
            return hubCryptoService.batchDecrypt(encryptedDataList, maskPolicyName, maskPolicyUid, includeStats);
        } catch (Exception e) {
            // 예외 발생 시 개별 복호화로 폴백 (상위에서 처리)
            throw new RuntimeException("배치 복호화 실패: " + e.getMessage(), e);
        }
    }
    
    /**
     * 배치 암호화 (Hub 암복호화 라이브러리 사용)
     * 
     * @param dataList 암호화할 평문 데이터 목록
     * @param policyList 각 데이터에 적용할 정책명 목록 (dataList와 동일한 크기)
     * @return 암호화된 데이터 목록 (순서 보장)
     */
    public java.util.List<String> batchEncrypt(java.util.List<String> dataList, 
                                                java.util.List<String> policyList) {
        if (dataList == null || dataList.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        
        if (policyList == null || policyList.size() != dataList.size()) {
            throw new IllegalArgumentException("정책 목록의 크기가 데이터 목록과 일치하지 않습니다");
        }
        
        try {
            return hubCryptoService.batchEncrypt(dataList, policyList);
        } catch (Exception e) {
            // 예외 발생 시 개별 암호화로 폴백 (상위에서 처리)
            throw new RuntimeException("배치 암호화 실패: " + e.getMessage(), e);
        }
    }
    
    /**
     * 데이터 암호화 (통계 정보 포함 옵션)
     * 
     * @param data 암호화할 데이터
     * @param policy 암호화 정책명
     * @param includeStats 통계 정보 포함 여부
     * @return 암호화된 데이터
     */
    public String encrypt(String data, String policy, boolean includeStats) {
        return hubCryptoService.encrypt(data, policy, includeStats);
    }
    
    /**
     * 데이터가 암호화된 형태인지 확인 (Hub 암복호화 라이브러리 사용)
     * 
     * @param data 확인할 데이터
     * @return 암호화된 데이터인지 여부
     */
    public boolean isEncryptedData(String data) {
        return hubCryptoService.isEncryptedData(data);
    }
}
