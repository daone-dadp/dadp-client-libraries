package com.dadp.common.sync.config;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class AtomicJsonFileTest {
 @TempDir Path dir;
 @Test void readersNeverObservePartialJsonAndExistingAccessIsRetained()throws Exception{
  Path target=dir.resolve("runtime.json");ObjectMapper mapper=new ObjectMapper();
  AtomicJsonFile.write(target,mapper,Collections.singletonMap("version",0));
  boolean posix=Files.getFileStore(target).supportsFileAttributeView("posix");
  if(posix)Files.setPosixFilePermissions(target,PosixFilePermissions.fromString("rw-r-----"));
  AtomicBoolean done=new AtomicBoolean();AtomicReference<Throwable> failure=new AtomicReference<>();
  Thread reader=new Thread(()->{try{while(!done.get())assertTrue(mapper.readTree(target.toFile()).has("version"));}catch(Throwable e){failure.set(e);}});
  reader.start();
  try{
   for(int i=1;i<=100;i++){
    Map<String,Object> data=new HashMap<>();data.put("version",i);data.put("payload",String.join("",Collections.nCopies(2000,"value")));
    AtomicJsonFile.write(target,mapper,data);
   }
  }finally{done.set(true);reader.join(5000);}
  assertNull(failure.get());assertFalse(reader.isAlive());assertEquals(100,mapper.readTree(target.toFile()).path("version").asInt());
  if(posix)assertEquals(PosixFilePermissions.fromString("rw-r-----"),Files.getPosixFilePermissions(target));
 }
 @Test void symlinkCannotRedirectRuntimePublication()throws Exception{
  Path outside=dir.resolve("outside.json");Files.write(outside,"original".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  Path link=dir.resolve("runtime.json");Files.createSymbolicLink(link,outside);
  assertThrows(java.io.IOException.class,()->AtomicJsonFile.write(link,new ObjectMapper(),Collections.singletonMap("version",9)));
  assertEquals("original",new String(Files.readAllBytes(outside),java.nio.charset.StandardCharsets.UTF_8));
 }
}
