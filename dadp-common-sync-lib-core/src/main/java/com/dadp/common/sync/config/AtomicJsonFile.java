package com.dadp.common.sync.config;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
/** Publish complete JSON while retaining an existing app owner's access. */
public final class AtomicJsonFile {
 private AtomicJsonFile(){}
 public static void write(Path target,ObjectMapper mapper,Object value)throws IOException {
  if(Files.isSymbolicLink(target))throw new IOException("Runtime JSON target must not be a symlink");
  Path temp=Files.createTempFile(target.getParent(),".runtime-json-",".tmp");
  try {
   try {
    if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){
     PosixFileAttributes attrs=Files.readAttributes(target,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
     Files.setOwner(temp,attrs.owner());
     Files.getFileAttributeView(temp,PosixFileAttributeView.class).setGroup(attrs.group());
     Files.setPosixFilePermissions(temp,attrs.permissions());
    }else Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));
   }catch(UnsupportedOperationException ignored){}
   mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),value);
   Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
  }finally{Files.deleteIfExists(temp);}
 }
}
