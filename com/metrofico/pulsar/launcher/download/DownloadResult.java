package com.metrofico.pulsar.launcher.download;

public record DownloadResult(DownloadRequest request, DownloadResult.Outcome outcome, long bytes) {
   public enum Outcome {
      CACHED,
      DOWNLOADED;
   }
}
