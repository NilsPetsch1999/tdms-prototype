package fhcampus.nilspetsch.tdms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import fhcampus.nilspetsch.tdms.service.SourceDatabaseConnectionService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Component
public class DemoSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSchemaInitializer.class);
    private static final List<String> FIRST_NAMES = List.of(
        "Nina", "Lukas", "Elena", "Sophie", "Jonas", "Mia", "David", "Lea", "Paul", "Anna"
    );
    private static final List<String> LAST_NAMES = List.of(
        "Huber", "Mayer", "Wagner", "Gruber", "Pichler", "Steiner", "Bauer", "Hofer", "Eder", "Fuchs"
    );
    private static final List<String> CITIES = List.of(
        "Vienna", "Linz", "Graz", "Salzburg", "Innsbruck", "Klagenfurt"
    );
    private static final List<String> SEGMENTS = List.of("RETAIL", "BUSINESS", "ENTERPRISE");
    private static final List<String> CATEGORIES = List.of("Electronics", "Office", "Accessories", "Software");
    private static final List<String> BRANDS = List.of("Nimbus", "Vista", "Orbit", "NorthSeat", "CoreLine");
    private static final List<String> ORDER_STATUSES = List.of("NEW", "PROCESSING", "PAID", "SHIPPED", "COMPLETED");
    private static final List<String> PAYMENT_METHODS = List.of("CREDIT_CARD", "PAYPAL", "INVOICE", "BANK_TRANSFER");

    private final SourceDatabaseConnectionService sourceDatabaseConnectionService;

    public DemoSchemaInitializer(SourceDatabaseConnectionService sourceDatabaseConnectionService) {
        this.sourceDatabaseConnectionService = sourceDatabaseConnectionService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            if (!sourceDatabaseConnectionService.isMySql()) {
                log.info("Skipping demo schema init because database is not MySQL.");
                return;
            }

            var jdbcTemplate = sourceDatabaseConnectionService.createJdbcTemplate();
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS demo");
            jdbcTemplate.execute("CREATE SCHEMA demo");
            createCustomersTable(jdbcTemplate);
            createProductsTable(jdbcTemplate);
            createOrdersTable(jdbcTemplate);
            createOrderItemsTable(jdbcTemplate);

            seedCustomers(jdbcTemplate);
            seedProducts(jdbcTemplate);
            seedOrders(jdbcTemplate);
            seedOrderItems(jdbcTemplate);

            log.info("Demo schema initialization finished.");
        } catch (Exception exception) {
            log.warn("Skipping demo schema initialization on the active source connection: {}", exception.getMessage());
        }
    }

    private void createCustomersTable(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS demo.customers (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                customer_number VARCHAR(30) NOT NULL UNIQUE,
                first_name VARCHAR(80) NOT NULL,
                last_name VARCHAR(80) NOT NULL,
                email VARCHAR(150) NOT NULL,
                phone VARCHAR(40) NULL,
                date_of_birth DATE NULL,
                street_address VARCHAR(150) NULL,
                city VARCHAR(120) NULL,
                postal_code VARCHAR(20) NULL,
                country_code CHAR(2) NOT NULL DEFAULT 'AT',
                segment VARCHAR(40) NOT NULL DEFAULT 'RETAIL',
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        );

    }

    private void createProductsTable(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS demo.products (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                sku VARCHAR(40) NOT NULL UNIQUE,
                name VARCHAR(150) NOT NULL,
                category VARCHAR(80) NOT NULL,
                brand VARCHAR(80) NULL,
                description VARCHAR(255) NULL,
                unit_price DECIMAL(12,2) NOT NULL,
                currency_code CHAR(3) NOT NULL DEFAULT 'EUR',
                stock_quantity INT NOT NULL DEFAULT 0,
                active BOOLEAN NOT NULL DEFAULT TRUE,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        );
    }

    private void createOrdersTable(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS demo.orders (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                customer_id BIGINT NOT NULL,
                order_number VARCHAR(40) NOT NULL,
                currency_code CHAR(3) NOT NULL DEFAULT 'EUR',
                subtotal_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
                shipping_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
                total_amount DECIMAL(12,2) NOT NULL,
                status VARCHAR(20) NOT NULL,
                payment_method VARCHAR(30) NULL,
                order_date DATE NOT NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES demo.customers (id)
            )
            """
        );

    }

    private void createOrderItemsTable(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS demo.order_items (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                order_id BIGINT NOT NULL,
                product_id BIGINT NOT NULL,
                quantity INT NOT NULL,
                unit_price DECIMAL(12,2) NOT NULL,
                line_total DECIMAL(12,2) NOT NULL,
                CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES demo.orders (id),
                CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES demo.products (id)
            )
            """
        );
    }

    private void seedCustomers(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        Integer customerCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo.customers", Integer.class);
        if (customerCount != null && customerCount == 0) {
            String sql =
                """
                INSERT INTO demo.customers (
                    customer_number,
                    first_name,
                    last_name,
                    email,
                    phone,
                    date_of_birth,
                    street_address,
                    city,
                    postal_code,
                    country_code,
                    segment
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
            for (int i = 1; i <= 55; i++) {
                String firstName = FIRST_NAMES.get((i - 1) % FIRST_NAMES.size());
                String lastName = LAST_NAMES.get(((i - 1) / FIRST_NAMES.size()) % LAST_NAMES.size());
                String city = CITIES.get((i - 1) % CITIES.size());
                String email = (firstName + "." + lastName + "." + i + "@example.org").toLowerCase();
                jdbcTemplate.update(
                    sql,
                    "CUST-" + String.format("%04d", 1000 + i),
                    firstName,
                    lastName,
                    email,
                    "+43 660 " + String.format("%07d", 1000000 + (i * 37)),
                    LocalDate.of(1980 + (i % 20), ((i - 1) % 12) + 1, ((i - 1) % 27) + 1),
                    "Sample Street " + i,
                    city,
                    String.valueOf(1000 + ((i * 73) % 8000)),
                    "AT",
                    SEGMENTS.get((i - 1) % SEGMENTS.size())
                );
            }
        }
    }

    private void seedProducts(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        Integer productCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo.products", Integer.class);
        if (productCount != null && productCount == 0) {
            String sql =
                """
                INSERT INTO demo.products (
                    sku,
                    name,
                    category,
                    brand,
                    description,
                    unit_price,
                    currency_code,
                    stock_quantity,
                    active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
            for (int i = 1; i <= 50; i++) {
                String category = CATEGORIES.get((i - 1) % CATEGORIES.size());
                String brand = BRANDS.get((i - 1) % BRANDS.size());
                BigDecimal price = BigDecimal.valueOf(19.99 + (i * 17.35)).setScale(2, RoundingMode.HALF_UP);
                jdbcTemplate.update(
                    sql,
                    "SKU-" + String.format("%05d", i),
                    brand + " " + category + " Item " + i,
                    category,
                    brand,
                    "Demo product " + i + " for synthetic data experiments",
                    price,
                    "EUR",
                    10 + (i * 3),
                    i % 11 != 0
                );
            }
        }
    }

    private void seedOrders(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        Integer orderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo.orders", Integer.class);
        if (orderCount != null && orderCount == 0) {
            String sql =
                """
                INSERT INTO demo.orders (
                    customer_id,
                    order_number,
                    currency_code,
                    subtotal_amount,
                    shipping_amount,
                    total_amount,
                    status,
                    payment_method,
                    order_date
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
            for (int i = 1; i <= 66; i++) {
                BigDecimal shipping = i % 3 == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(4.90 + (i % 4) * 2.5).setScale(2, RoundingMode.HALF_UP);
                jdbcTemplate.update(
                    sql,
                    ((i - 1) % 55) + 1L,
                    "PO-" + String.format("%05d", 10000 + i),
                    "EUR",
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    shipping,
                    shipping,
                    ORDER_STATUSES.get((i - 1) % ORDER_STATUSES.size()),
                    PAYMENT_METHODS.get((i - 1) % PAYMENT_METHODS.size()),
                    LocalDate.of(2025, ((i - 1) % 12) + 1, ((i - 1) % 27) + 1)
                );
            }
        }
    }

    private void seedOrderItems(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        Integer orderItemCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo.order_items", Integer.class);
        if (orderItemCount != null && orderItemCount == 0) {
            String sql =
                """
                INSERT INTO demo.order_items (order_id, product_id, quantity, unit_price, line_total)
                VALUES (?, ?, ?, ?, ?)
                """;
            int orderItemIndex = 0;
            for (int orderId = 1; orderId <= 66; orderId++) {
                int itemCountForOrder = orderId <= 27 ? 2 : 1;
                for (int itemIndex = 0; itemIndex < itemCountForOrder; itemIndex++) {
                    orderItemIndex++;
                    long productId = (((orderId - 1) * 2L + itemIndex) % 50) + 1L;
                    int quantity = ((orderId + itemIndex) % 4) + 1;
                    BigDecimal unitPrice = BigDecimal.valueOf(19.99 + (productId * 17.35)).setScale(2, RoundingMode.HALF_UP);
                    jdbcTemplate.update(
                        sql,
                        (long) orderId,
                        productId,
                        quantity,
                        unitPrice,
                        unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP)
                    );
                }
            }

            if (orderItemIndex != 93) {
                throw new IllegalStateException("Expected to seed 93 order items but seeded " + orderItemIndex);
            }

            jdbcTemplate.update(
                """
                UPDATE demo.orders o
                LEFT JOIN (
                    SELECT order_id,
                           SUM(line_total) AS subtotal
                    FROM demo.order_items
                    GROUP BY order_id
                ) totals ON totals.order_id = o.id
                SET o.subtotal_amount = COALESCE(totals.subtotal, 0.00),
                    o.total_amount = COALESCE(totals.subtotal, 0.00) + o.shipping_amount
                """
            );

            jdbcTemplate.update(
                """
                UPDATE demo.orders
                SET status = CASE
                    WHEN id % 5 = 0 THEN 'COMPLETED'
                    WHEN id % 5 = 1 THEN 'PAID'
                    WHEN id % 5 = 2 THEN 'SHIPPED'
                    WHEN id % 5 = 3 THEN 'PROCESSING'
                    ELSE 'NEW'
                END
                """
            );
        }
    }

}
