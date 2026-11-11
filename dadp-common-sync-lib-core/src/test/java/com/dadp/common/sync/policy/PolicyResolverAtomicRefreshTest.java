package com.dadp.common.sync.policy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PolicyResolverAtomicRefreshTest {
 @TempDir Path directory;
 @Test void readersNeverObserveTheEmptyOrPartiallyRebuiltPolicyCache() throws Exception {
  PolicyResolver resolver=new PolicyResolver(directory.toString(),"policies.json");
  Map<String,String> expected=new HashMap<>();
  for(int i=0;i<100;i++)expected.put("public.users.c"+i,"DAONE234");
  resolver.refreshMappings(expected,1L);
  ExecutorService pool=Executors.newFixedThreadPool(2);
  AtomicBoolean done=new AtomicBoolean();
  CountDownLatch ready=new CountDownLatch(1);
  Future<?> reader=pool.submit(()->{
   ready.countDown();
   do {
    assertEquals(expected,resolver.getAllMappings());
   } while(!done.get());
  });
  Future<?> writer=pool.submit(()->{
   try {
    ready.await();
    for(int i=0;i<2000;i++)resolver.refreshMappings(expected,1L);
   } catch(InterruptedException e){throw new AssertionError(e);}
   finally{done.set(true);}
  });
  try {writer.get(30,TimeUnit.SECONDS);reader.get(30,TimeUnit.SECONDS);}
  finally {done.set(true);pool.shutdownNow();}
 }
}
