package vn.edu.bookstore.order.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import vn.edu.bookstore.order.api.CreateOrderRequest;
import vn.edu.bookstore.order.api.OrderView;
import vn.edu.bookstore.order.client.BookInfo;
import vn.edu.bookstore.order.client.CatalogClient;
import vn.edu.bookstore.order.domain.OrderEntity;
import vn.edu.bookstore.order.domain.OrderRepository;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final CatalogClient catalog;
    private final TransactionTemplate tx;

    public OrderService(OrderRepository orders, CatalogClient catalog, TransactionTemplate tx) {
        this.orders = orders;
        this.catalog = catalog;
        this.tx = tx;
    }

    public PlaceOrderResult place(long customerId, CreateOrderRequest request, String idempotencyKey) {

        String hash = requestHash(customerId, request);

        // TH3-4: Kiểm tra Idempotency-Key trước khi gọi Catalog.
        if (idempotencyKey != null) {
            Optional<PlaceOrderResult> existing = replay(idempotencyKey, hash);

            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // Gọi catalog-service ngoài giao dịch CSDL.
        List<Priced> priced = new ArrayList<>();

        for (CreateOrderRequest.Line line : request.lines()) {
            BookInfo book = catalog.getBook(line.isbn());

            priced.add(new Priced(
                    line.isbn(),
                    line.quantity(),
                    book.price().amount(),
                    book.price().currency()
            ));
        }

        String currency = priced.getFirst().currency();

        if (priced.stream().anyMatch(p -> !p.currency().equals(currency))) {
            throw new InvalidOrderException("Mọi dòng của một đơn phải cùng loại tiền tệ");
        }

        try {
            OrderView view = tx.execute(status -> {

                OrderEntity order = new OrderEntity(
                        orders.nextOrderId(),
                        customerId,
                        currency,
                        idempotencyKey,
                        hash
                );

                priced.forEach(p ->
                        order.addLine(p.isbn(), p.quantity(), p.unitPrice())
                );

                orders.save(order);

                return OrderView.from(order);
            });

            return new PlaceOrderResult(view, false);

        } catch (DataIntegrityViolationException race) {

            // Xử lý trường hợp hai yêu cầu cùng Idempotency-Key đến đồng thời.
            return replay(idempotencyKey, hash).orElseThrow(() -> race);
        }
    }

    private Optional<PlaceOrderResult> replay(String key, String hash) {

        if (key == null) {
            return Optional.empty();
        }

        return tx.execute(status ->
                orders.findByIdempotencyKey(key).map(existing -> {

                    // Cùng khóa nhưng nội dung khác nhau.
                    if (!hash.equals(existing.getRequestHash())) {
                        throw new IdempotencyConflictException(key);
                    }

                    // Cùng khóa, cùng nội dung: trả lại đơn cũ.
                    return new PlaceOrderResult(
                            OrderView.from(existing),
                            true
                    );
                })
        );
    }

    public OrderView get(long id) {
        return tx.execute(status ->
                orders.findById(id)
                        .map(OrderView::from)
                        .orElseThrow(() -> new OrderNotFoundException(id))
        );
    }

    public List<OrderView> listByCustomer(long customerId) {
        return tx.execute(status ->
                orders.findByCustomerIdOrderByIdDesc(customerId)
                        .stream()
                        .map(OrderView::from)
                        .toList()
        );
    }

    // Tạo SHA-256 từ customerId và danh sách sản phẩm.
    static String requestHash(long customerId, CreateOrderRequest request) {

        StringBuilder sb = new StringBuilder().append(customerId);

        request.lines()
                .stream()
                .map(line -> line.isbn() + "x" + line.quantity())
                .sorted()
                .forEach(value -> sb.append("|").append(value));

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Priced(
            String isbn,
            int quantity,
            BigDecimal unitPrice,
            String currency
    ) {
    }
}
