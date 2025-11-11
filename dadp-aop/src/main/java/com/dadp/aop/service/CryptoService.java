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
        if (encryptedData == null) {
            return null;
        }
        
        try {
            // Hub/Engine에서 암호화 여부 판단 및 처리
            String decrypted = hubCryptoService.decrypt(encryptedData, maskPolicyName, maskPolicyUid);
            
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
     * 데이터가 암호화된 형태인지 확인 (Hub 암복호화 라이브러리 사용)
     * 
     * @param data 확인할 데이터
     * @return 암호화된 데이터인지 여부
     */
    public boolean isEncryptedData(String data) {
        return hubCryptoService.isEncryptedData(data);
    }
}
