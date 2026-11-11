package vn.edu.bookstore.order.client;

import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CatalogClient {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    public CatalogClient(
            RestClient catalogRestClient,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory) {

        this.restClient = catalogRestClient;
        this.circuitBreaker = circuitBreakerFactory.create("catalog");
    }

    public BookInfo getBook(String isbn) {

        return circuitBreaker.run(
                () -> restClient.get()
                        .uri("/api/v1/books/{isbn}", isbn)
                        .retrieve()
                        .onStatus(
                                s -> s.value() == HttpStatus.NOT_FOUND.value(),
                                (req, res) -> {
                                    throw new UnknownBookException(isbn);
                                })
                        .body(BookInfo.class),

                failure -> {
                    if (failure instanceof UnknownBookException unknown) {
                        throw unknown;
                    }

                    throw new CatalogUnavailableException(
                            "catalog-service không sẵn sàng: "
                                    + failure.getClass().getSimpleName(),
                            failure);
                });
    }
}
