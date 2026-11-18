package com.example.claims;

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
 * Hosts JAX-RS resources from a plain Java program: Jersey 2 on the JDK's built-in HTTP server. Main gives it
 * the claims page ({@link ClaimResource}) and Skipper's admin UI, which is a JAX-RS resource on the javax.ws.rs
 * namespace. A service that already runs Jersey 2 registers the same resources with its own container instead.
 */
public final class WebServer {
  // Jersey logs through java.util.logging and warns on startup about optional features this program does
  // not use, and about the admin resource's root path. Held in a field so the level is not garbage-collected.
  private static final Logger JERSEY_LOG = Logger.getLogger("org.glassfish.jersey");

  private WebServer() {}

  /**
   * Listens on the loopback interface only: the admin UI can cancel, retry and delete instances, and the
   * claims page pays claims. Port 0 picks a free one.
   */
  public static HttpServer start(int port, Object... resources) throws IOException {
    JERSEY_LOG.setLevel(Level.SEVERE);
    // Jackson reads and writes the claims page's JSON. The admin resource writes its own and only needs it
    // to read the id lists its bulk actions post.
    ResourceConfig config = new ResourceConfig().register(JacksonFeature.class);
    for (Object resource : resources) {
      config.register(resource);
    }
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    server.createContext("/", ContainerFactory.createContainer(HttpHandler.class, config));
    server.start();
    return server;
  }

  public static String url(HttpServer server) {
    return "http://localhost:" + server.getAddress().getPort() + "/";
  }
}
