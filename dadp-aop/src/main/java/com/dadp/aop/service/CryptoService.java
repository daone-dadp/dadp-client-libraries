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
        return hubCryptoService.decrypt(encryptedData);
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
