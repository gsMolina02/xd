package com.metrofico.pulsar.bootstrap.loader;

import com.metrofico.pulsar.bootstrap.transform.TransformerChain;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.jar.Attributes.Name;

public final class GameClassLoader extends SecureClassLoader {
   public static final Set<String> DEFAULT_PARENT_FIRST = Set.of(
      "java.", "jdk.", "sun.", "org.objectweb.asm.", "org.spongepowered.asm.", "com.metrofico.pulsar.bootstrap.", "com.metrofico.pulsar.api."
   );
   private final URLClassLoader resources;
   private final Set<String> parentFirst;
   private final TransformerChain transformers;
   private final Map<URL, Optional<Manifest>> manifests = new ConcurrentHashMap<>();
   private final Map<String, byte[]> transformedBytes = new ConcurrentHashMap<>();
   private final Map<String, byte[]> redefinedBytes = new ConcurrentHashMap<>();
   private final ThreadLocal<String> defining = new ThreadLocal<>();
   private final AtomicInteger defined = new AtomicInteger();

   public GameClassLoader(URL[] classpath, ClassLoader parent, Set<String> parentFirst, TransformerChain transformers) {
      super("pulsar-game", parent);
      this.resources = new URLClassLoader("pulsar-game-resources", classpath, null);
      this.parentFirst = Set.copyOf(parentFirst);
      this.transformers = transformers;
   }

   public GameClassLoader(URL[] classpath, ClassLoader parent) {
      this(classpath, parent, DEFAULT_PARENT_FIRST, new TransformerChain());
   }

   public TransformerChain transformers() {
      return this.transformers;
   }

   public boolean isParentFirst(String className) {
      for (String prefix : this.parentFirst) {
         if (className.startsWith(prefix)) {
            return true;
         }
      }

      return false;
   }

   public boolean parallelCapable() {
      return this.isRegisteredAsParallelCapable();
   }

   @Override
   protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (this.getClassLoadingLock(name)) {
         Class<?> loaded = this.findLoadedClass(name);
         if (loaded == null) {
            if (this.isParentFirst(name)) {
               loaded = this.getParent().loadClass(name);
            } else {
               try {
                  loaded = this.findClass(name);
               } catch (ClassNotFoundException notInGame) {
                  loaded = this.getParent().loadClass(name);
               }
            }
         }

         if (resolve) {
            this.resolveClass(loaded);
         }

         return loaded;
      }
   }

   @Override
   protected Class<?> findClass(String name) throws ClassNotFoundException {
      URL url = this.resources.findResource(name.replace('.', '/') + ".class");
      if (url == null) {
         throw new ClassNotFoundException(name);
      }

      byte[] bytes;
      try (InputStream in = url.openStream()) {
         bytes = in.readAllBytes();
      } catch (IOException e) {
         throw new ClassNotFoundException(name + " (unreadable at " + url + ")", e);
      }

      byte[] transformed = this.transformers.transform(name, bytes);
      if (transformed != bytes && !Arrays.equals(transformed, bytes)) {
         this.transformedBytes.put(name, transformed);
      }

      URL origin = jarOf(url);
      this.definePackageIfNeeded(name, origin);
      String outer = this.defining.get();
      this.defining.set(name);

      try {
         Class<?> c = this.defineClass(name, transformed, 0, transformed.length, new CodeSource(origin, (CodeSigner[])null));
         this.defined.incrementAndGet();
         return c;
      } finally {
         this.defining.set(outer);
      }
   }

   public int definedCount() {
      return this.defined.get();
   }

   public boolean isDefining(String name) {
      return name.equals(this.defining.get());
   }

   public void recordRedefinition(String name, byte[] bytes) {
      this.redefinedBytes.put(name, (byte[])bytes.clone());
   }

   public byte[] rawClassBytes(String name) throws ClassNotFoundException {
      String path = name.replace('.', '/') + ".class";
      URL url = this.resources.findResource(path);
      if (url == null) {
         url = this.getParent().getResource(path);
      }

      if (url == null) {
         throw new ClassNotFoundException(name);
      }

      try (InputStream in = url.openStream()) {
         return in.readAllBytes();
      } catch (IOException e) {
         throw new ClassNotFoundException(name, e);
      }
   }

   public byte[] definedClassBytes(String name) throws ClassNotFoundException {
      byte[] redefined = this.redefinedBytes.get(name);
      if (redefined != null) {
         return (byte[])redefined.clone();
      }

      if (!this.isParentFirst(name) && this.hasClass(name) && this.findLoadedClass(name) == null) {
         this.loadClass(name, false);
      }

      byte[] transformed = this.transformedBytes.get(name);
      return transformed != null ? (byte[])transformed.clone() : this.rawClassBytes(name);
   }

   public Set<String> transformedClassNames() {
      return Set.copyOf(this.transformedBytes.keySet());
   }

   public boolean isLoaded(String name) {
      return this.findLoadedClass(name) != null;
   }

   public boolean hasClass(String name) {
      return this.resources.findResource(name.replace('.', '/') + ".class") != null;
   }

   @Override
   public URL getResource(String name) {
      URL url = this.resources.findResource(name);
      return url != null ? url : this.getParent().getResource(name);
   }

   @Override
   public Enumeration<URL> getResources(String name) throws IOException {
      List<URL> all = new ArrayList<>(Collections.list(this.resources.findResources(name)));
      all.addAll(Collections.list(this.getParent().getResources(name)));
      return Collections.enumeration(all);
   }

   private void definePackageIfNeeded(String className, URL origin) {
      int dot = className.lastIndexOf(46);
      if (dot > 0 && this.getDefinedPackage(className.substring(0, dot)) == null) {
         String pkg = className.substring(0, dot);

         try {
            Manifest manifest = this.manifests.computeIfAbsent(origin, GameClassLoader::readManifest).orElse(null);
            if (manifest != null) {
               String path = pkg.replace('.', '/') + "/";
               Attributes entry = manifest.getAttributes(path);
               Attributes main = manifest.getMainAttributes();
               this.definePackage(
                  pkg,
                  attr(entry, main, Name.SPECIFICATION_TITLE),
                  attr(entry, main, Name.SPECIFICATION_VERSION),
                  attr(entry, main, Name.SPECIFICATION_VENDOR),
                  attr(entry, main, Name.IMPLEMENTATION_TITLE),
                  attr(entry, main, Name.IMPLEMENTATION_VERSION),
                  attr(entry, main, Name.IMPLEMENTATION_VENDOR),
                  "true".equalsIgnoreCase(attr(entry, main, Name.SEALED)) ? origin : null
               );
            } else {
               this.definePackage(pkg, null, null, null, null, null, null, null);
            }
         } catch (IllegalArgumentException var9) {
         }
      }
   }

   private static String attr(Attributes entry, Attributes main, Name name) {
      String value = entry != null ? entry.getValue(name) : null;
      return value != null ? value : main.getValue(name);
   }

   private static Optional<Manifest> readManifest(URL origin) {
      if ("file".equals(origin.getProtocol()) && origin.getPath().endsWith(".jar")) {
         try (JarFile jar = new JarFile(new File(origin.toURI()))) {
            return Optional.ofNullable(jar.getManifest());
         } catch (IOException | URISyntaxException e) {
            return Optional.empty();
         }
      } else {
         return Optional.empty();
      }
   }

   private static URL jarOf(URL classUrl) {
      try {
         if ("jar".equals(classUrl.getProtocol())) {
            String spec = classUrl.getFile();
            int bang = spec.indexOf("!/");
            return URI.create(bang > 0 ? spec.substring(0, bang) : spec).toURL();
         } else {
            return classUrl;
         }
      } catch (IOException | IllegalArgumentException e) {
         return classUrl;
      }
   }

   static {
      registerAsParallelCapable();
   }
}
