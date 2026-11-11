package com.dadp.hub.crypto;
import java.net.URI;
import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
class CryptoRetryInterceptorTest {
 @Test void transient503AndConnectionRefusedRecoverWithIdenticalBody() throws Exception {
  AtomicInteger calls=new AtomicInteger();
  byte[] body=new byte[]{1,2,3};
  CryptoRetryInterceptor i=new CryptoRetryInterceptor(1000,5,1);
  MockClientHttpRequest q=new MockClientHttpRequest(HttpMethod.POST,URI.create("https://localhost/api/encrypt/batch"));
  assertEquals(200,i.intercept(q,body,(r,b)->{
   assertArrayEquals(body,b);int n=calls.incrementAndGet();
   if(n==1)throw new ConnectException("refused");
   return new MockClientHttpResponse(new byte[0],n==2?HttpStatus.SERVICE_UNAVAILABLE:HttpStatus.OK);
  }).getRawStatusCode());
  assertEquals(3,calls.get());
 }
 @Test void authenticationAndInputErrorsNeverRetry() throws Exception {
  for(HttpStatus status:new HttpStatus[]{HttpStatus.BAD_REQUEST,HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN}){
   AtomicInteger calls=new AtomicInteger();
   new CryptoRetryInterceptor(1000,5,1).intercept(new MockClientHttpRequest(HttpMethod.POST,URI.create("https://localhost/api/encrypt")),new byte[0],(r,b)->{calls.incrementAndGet();return new MockClientHttpResponse(new byte[0],status);});
   assertEquals(1,calls.get());
  }
 }
 @Test void certificateFailureNeverRetries() {
  AtomicInteger calls=new AtomicInteger();
  assertThrows(SSLHandshakeException.class,()->new CryptoRetryInterceptor(1000,5,1).intercept(new MockClientHttpRequest(HttpMethod.POST,URI.create("https://localhost/api/encrypt")),new byte[0],(r,b)->{calls.incrementAndGet();throw new SSLHandshakeException("untrusted");}));
  assertEquals(1,calls.get());
 }
 @Test void noManagementOrSqlReplayAndAttemptsBounded() throws Exception {
  AtomicInteger calls=new AtomicInteger();
  CryptoRetryInterceptor i=new CryptoRetryInterceptor(1000,3,1);
  i.intercept(new MockClientHttpRequest(HttpMethod.POST,URI.create("https://localhost/api/keys")),new byte[0],(r,b)->{calls.incrementAndGet();return new MockClientHttpResponse(new byte[0],HttpStatus.SERVICE_UNAVAILABLE);});
  assertEquals(1,calls.get());calls.set(0);
  i.intercept(new MockClientHttpRequest(HttpMethod.POST,URI.create("https://localhost/api/decrypt")),new byte[0],(r,b)->{calls.incrementAndGet();return new MockClientHttpResponse(new byte[0],HttpStatus.SERVICE_UNAVAILABLE);});
  assertEquals(3,calls.get());
 }
}
