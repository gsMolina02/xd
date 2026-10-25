package com.metrofico.pulsar.api.model;

import java.util.Objects;

public record EngineInfo(String backendId, GameVersion gameVersion, int javaFeatureVersion) {
   public EngineInfo {
      Objects.requireNonNull(backendId, "backendId");
      Objects.requireNonNull(gameVersion, "gameVersion");
      if (backendId.isBlank()) {
         throw new IllegalArgumentException("backendId must not be blank");
      }
   }

   @Override
   public String toString() {
      return this.backendId + " for Minecraft " + this.gameVersion + " on Java " + this.javaFeatureVersion;
   }
}
