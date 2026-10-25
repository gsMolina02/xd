package com.metrofico.pulsar.launcher.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public record UpdatePlan(ReleaseManifest.Package pkg, UpdatePlan.Installation installation, UpdatePlan.Apply apply) {
   public static final String MSIX = "msix";

   public static Optional<UpdatePlan> plan(ReleaseManifest manifest, UpdatePlan.Installation inst) {
      for (String f : switch (inst.format()) {
         case "appimage" -> List.of("appimage", "tar.gz");
         case "tar.gz" -> List.of("tar.gz", "appimage");
         case "deb" -> List.of("deb");
         case "setup.exe", "zip" -> List.of("setup.exe", "zip");
         case "msix" -> List.of();
         default -> List.of("dmg");
      }) {
         Optional<ReleaseManifest.Package> p = manifest.packages()
            .stream()
            .filter(x -> x.os().equals(inst.os()) && x.arch().equals(inst.arch()) && x.format().equals(f))
            .findFirst();
         if (p.isPresent()) {
            return Optional.of(new UpdatePlan(p.get(), inst, applyFor(p.get().format())));
         }
      }

      return Optional.empty();
   }

   static UpdatePlan.Apply applyFor(String format) {
      return switch (format) {
         case "appimage" -> UpdatePlan.Apply.REPLACE_APPIMAGE;
         case "tar.gz", "zip" -> UpdatePlan.Apply.SWAP_DIRECTORY;
         case "deb" -> UpdatePlan.Apply.OPEN_PACKAGE;
         case "setup.exe" -> UpdatePlan.Apply.RUN_INSTALLER;
         default -> UpdatePlan.Apply.MOUNT_AND_COPY;
      };
   }

   public Path installDir() {
      Path t = this.installation.target();
      return t != null && Files.isRegularFile(t) ? t.getParent() : t;
   }

   public enum Apply {
      REPLACE_APPIMAGE,
      SWAP_DIRECTORY,
      OPEN_PACKAGE,
      RUN_INSTALLER,
      MOUNT_AND_COPY;
   }

   public record Installation(String os, String arch, String format, Path target) {
      public static UpdatePlan.Installation detect(String osName, String osArch, String formatEnv, String appImagePath, Path executable) {
         String os = osName.toLowerCase(Locale.ROOT).contains("win") ? "windows" : (osName.toLowerCase(Locale.ROOT).contains("mac") ? "macos" : "linux");
         String arch = !osArch.toLowerCase(Locale.ROOT).contains("aarch64") && !osArch.toLowerCase(Locale.ROOT).contains("arm64") ? "x86_64" : "arm64";
         String format = formatEnv;
         Path target = executable;
         if (format == null || format.isBlank()) {
            if (appImagePath != null && !appImagePath.isBlank()) {
               format = "appimage";
               target = Path.of(appImagePath);
            } else if (os.equals("linux") && executable != null && executable.startsWith("/opt/pulsar-launcher")) {
               format = "deb";
            } else if (os.equals("linux")) {
               format = "tar.gz";
            } else if (os.equals("windows")) {
               format = "setup.exe";
            } else {
               format = "dmg";
            }
         }

         return new UpdatePlan.Installation(os, arch, format, target);
      }
   }
}
