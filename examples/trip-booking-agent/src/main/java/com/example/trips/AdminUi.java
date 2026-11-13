package com.example.trips;

import com.airbnb.skipper.factory.SkipperRuntime;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ContainerFactory;
import org.glassfish.jersey.server.ResourceConfig;

/**
 * Serves Skipper's admin UI: the dashboard, the instance list, and each instance's checkpoints, timers and
 * signals. The UI is a JAX-RS resource on the javax.ws.rs namespace, so a plain Java program hosts it with
 * Jersey 2 on the JDK's built-in HTTP server. A service that already runs Jersey 2 registers the same
 * resource with its own container instead.
 */
public final class AdminUi {
  // Jersey logs through java.util.logging and warns on startup about optional features this program does
  // not use, and about the admin resource's root path. Held in a field so the level is not garbage-collected.
  private static final Logger JERSEY_LOG = Logger.getLogger("org.glassfish.jersey");

  private AdminUi() {}

  /** Starts the UI on the loopback interface only: it can cancel, retry and delete instances. Port 0 picks a free one. */
  public static HttpServer start(SkipperRuntime runtime, int port) throws IOException {
    JERSEY_LOG.setLevel(Level.SEVERE);
    ResourceConfig resources = new ResourceConfig()
        .register(runtime.getAdminResource().get())
        // The resource writes its own JSON. This only reads the id lists the UI posts for its bulk actions.
        .register(JacksonFeature.class);
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    server.createContext("/", ContainerFactory.createContainer(HttpHandler.class, resources));
    server.start();
    return server;
  }

  public static String url(HttpServer server) {
    return "http://localhost:" + server.getAddress().getPort() + "/skipper/admin/";
  }
}
