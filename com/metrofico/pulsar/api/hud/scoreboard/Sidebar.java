package com.metrofico.pulsar.api.hud.scoreboard;

import java.util.List;

public record Sidebar(SidebarText title, List<Sidebar.Row> rows) {
   public Sidebar {
      rows = List.copyOf(rows);
   }

   public record Row(SidebarText name, SidebarText score) {
   }
}
