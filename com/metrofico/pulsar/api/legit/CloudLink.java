package com.metrofico.pulsar.api.legit;

public interface CloudLink {
   void open(CloudLink.Listener var1) throws Exception;

   void send(String var1);

   void close();

   interface Listener {
      void onText(String var1);

      void onClosed(String var1);
   }
}
