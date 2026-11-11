package vn.edu.bookstore.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayRoutingTest {

    private static HttpServer mockBackend;

    private static final AtomicReference<String> LAST_PATH =
            new AtomicReference<>();

    @Autowired
    private WebTestClient webTestClient;

    @BeforeAll
    static void startMockBackend() throws IOException {
        mockBackend = HttpServer.create(new InetSocketAddress(0), 0);

        mockBackend.createContext("/", exchange -> {
            LAST_PATH.set(exchange.getRequestURI().getPath());

            byte[] response = "OK".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        mockBackend.start();
    }

    @AfterAll
    static void stopMockBackend() {
        if (mockBackend != null) {
            mockBackend.stop(0);
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String mockUrl =
                "http://localhost:" + mockBackend.getAddress().getPort();

        registry.add("bookstore.routes.catalog", () -> mockUrl);
        registry.add("bookstore.routes.orders", () -> mockUrl);
        registry.add("bookstore.routes.inventory", () -> mockUrl);
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
    }

    @Test
    void dinhTuyenCatalog() {
        webTestClient.get()
                .uri("/api/v1/books/9780134685991")
                .exchange()
                .expectStatus().isOk();

        assertThat(LAST_PATH.get())
                .isEqualTo("/api/v1/books/9780134685991");
    }

    @Test
    void dinhTuyenOrders() {
        webTestClient.get()
                .uri("/api/v1/orders/1001")
                .exchange()
                .expectStatus().isOk();

        assertThat(LAST_PATH.get())
                .isEqualTo("/api/v1/orders/1001");
    }
}
