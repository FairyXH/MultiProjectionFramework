package io.github.libxposed.api;
import java.lang.reflect.Method;
import java.util.*;
public abstract class XposedModule {
 public static final Map<String,Integer> attempts=new HashMap<>(), installed=new HashMap<>();
 public static boolean failFirst;
 public interface ModuleLoadedParam { String getProcessName(); }
 public interface PackageLoadedParam {
  String getPackageName();
  default boolean isFirstPackage(){return true;}
  default android.content.pm.ApplicationInfo getApplicationInfo(){return new android.content.pm.ApplicationInfo();}
 }
 public interface PackageReadyParam extends PackageLoadedParam {}
 public void onModuleLoaded(ModuleLoadedParam p){}
 public void onPackageLoaded(PackageLoadedParam p){}
 public void onPackageReady(PackageReadyParam p){}
 public String getFrameworkName(){return "Test";}
 public String getFrameworkVersion(){return "1";}
 public int getApiVersion(){return 102;}
 public void log(int l,String t,String s){}
 public void log(int l,String t,String s,Throwable e){}
 public HookBuilder hook(Method m){return new HookBuilder(m.getName());}
 public interface Chain {
  Object proceed() throws Throwable;
  Object getThisObject();
  List<Object> getArgs();
  Object getArg(int i);
 }
 public interface Interceptor { Object apply(Chain c) throws Throwable; }
 public static final class HookBuilder {
  private final String name;
  HookBuilder(String n){name=n;}
  public HookBuilder setPriority(int p){return this;}
  public HookBuilder setExceptionMode(XposedInterface.ExceptionMode m){return this;}
  public void intercept(Interceptor i){
   int count=attempts.merge(name,1,Integer::sum);
   if(failFirst && count==1)throw new IllegalStateException("simulated registration failure");
   installed.merge(name,1,Integer::sum);
  }
 }
}
