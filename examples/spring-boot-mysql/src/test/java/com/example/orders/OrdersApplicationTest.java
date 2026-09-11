package com.example.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.orders.web.OrderController.OrderStatus;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * Drives the whole stack over HTTP against a real MySQL: Spring Boot, Flyway with Skipper's migrations, the
 * MySQL store and scheduler, and the Spring-backed injector. Needs Docker.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OrdersApplicationTest {
  @LocalServerPort int port;
  @Autowired InMemoryInventory inventory;
  @Autowired InMemoryPayments payments;

  private RestClient client() {
    return RestClient.create("http://localhost:" + port);
  }

  @Test
  void smallOrderIsFulfilled() {
    OrderStatus accepted = client().post().uri("/orders")
        .body(new OrderRequest("sb-small", "alice", "BOOK-1", 2, 3_900))
        .retrieve().body(OrderStatus.class);
    assertThat(accepted.orderId()).isEqualTo("sb-small");

    OrderStatus done = awaitInstanceStatus("sb-small", "COMPLETED");
    assertThat(done.result().getStatus()).isEqualTo("FULFILLED");
    assertThat(done.result().getTrackingId()).startsWith("trk-");
    // The in-memory services are shared singletons across the tests in this JVM, so match on content,
    // not on the sequence number of the reservation id.
    assertThat(inventory.entries()).anyMatch(e -> e.startsWith("RESERVE") && e.endsWith("2 x BOOK-1"));
  }

  @Test
  void largeOrderWaitsForApprovalSignal() {
    client().post().uri("/orders")
        .body(new OrderRequest("sb-large", "bob", "LAPTOP-9", 1, 149_900))
        .retrieve().toBodilessEntity();

    OrderStatus waiting = awaitInstanceStatus("sb-large", "WAITING");
    assertThat(waiting.stage()).isEqualTo("AWAITING_APPROVAL");
    assertThat(waiting.result()).isNull();

    client().post().uri("/orders/sb-large/approve?decision=true").retrieve().toBodilessEntity();

    OrderStatus done = awaitInstanceStatus("sb-large", "COMPLETED");
    assertThat(done.result().getStatus()).isEqualTo("FULFILLED");
  }

  @Test
  void refusedShipmentIsCompensated() {
    client().post().uri("/orders")
        .body(new OrderRequest("sb-hazmat", "carol", "HAZMAT-7", 1, 12_000))
        .retrieve().toBodilessEntity();

    awaitInstanceStatus("sb-hazmat", "COMPENSATION_COMPLETED");

    List<String> refunds = payments.entries().stream().filter(e -> e.startsWith("REFUND")).toList();
    List<String> releases = inventory.entries().stream().filter(e -> e.startsWith("RELEASE")).toList();
    assertThat(refunds).hasSize(1);
    assertThat(releases).hasSize(1);
  }

  private OrderStatus awaitInstanceStatus(String orderId, String expected) {
    return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
        .until(() -> client().get().uri("/orders/" + orderId).retrieve().body(OrderStatus.class),
            s -> expected.equals(s.instanceStatus()));
  }
}
