package fhcampus.nilspetsch.tdms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import fhcampus.nilspetsch.tdms.service.SourceDatabaseConnectionService;

@Component
public class DemoSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSchemaInitializer.class);

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
            jdbcTemplate.update(
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
                )
                VALUES
                    ('CUST-1001', 'Nina', 'Huber', 'nina.huber@example.org', '+43 660 1234567', '1995-04-11', 'Mariahilfer Strasse 12', 'Vienna', '1060', 'AT', 'RETAIL'),
                    ('CUST-1002', 'Lukas', 'Mayer', 'lukas.mayer@example.org', '+43 699 2345678', '1982-09-23', 'Landstrasse 44', 'Linz', '4020', 'AT', 'BUSINESS'),
                    ('CUST-1003', 'Elena', 'Wagner', 'elena.wagner@example.org', '+43 676 3456789', '1989-01-15', 'Annenstrasse 8', 'Graz', '8020', 'AT', 'RETAIL')
                """
            );
        }
    }

    private void seedProducts(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        Integer productCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo.products", Integer.class);
        if (productCount != null && productCount == 0) {
            jdbcTemplate.update(
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
                )
                VALUES
                    ('LAP-14-PRO', 'Nimbus Pro 14 Laptop', 'Electronics', 'Nimbus', '14-inch business laptop with 16GB RAM and 512GB SSD', 1299.00, 'EUR', 25, TRUE),
                    ('MON-27-4K', 'Vista 27 4K Monitor', 'Electronics', 'Vista', '27-inch UHD monitor with USB-C docking', 349.90, 'EUR', 40, TRUE),
                    ('DOC-USB-C', 'Docking Station USB-C', 'Accessories', 'Orbit', 'USB-C docking station with HDMI and Ethernet', 149.50, 'EUR', 60, TRUE),
                    ('CHR-ERG-01', 'Ergo Comfort Chair', 'Office', 'NorthSeat', 'Ergonomic office chair with lumbar support', 289.00, 'EUR', 18, TRUE)
                """
            );
        }
    }

    private void seedOrders(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        Integer orderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo.orders", Integer.class);
        if (orderCount != null && orderCount == 0) {
            jdbcTemplate.update(
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
                )
                SELECT c.id, seed.order_number, 'EUR', seed.subtotal_amount, seed.shipping_amount, seed.total_amount, seed.status, seed.payment_method, seed.order_date
                FROM (
                    SELECT 'nina.huber@example.org' AS customer_email, 'PO-10001' AS order_number, 1648.90 AS subtotal_amount, 0.00 AS shipping_amount, 1648.90 AS total_amount, 'PAID' AS status, 'CREDIT_CARD' AS payment_method, DATE('2025-10-10') AS order_date
                    UNION ALL
                    SELECT 'lukas.mayer@example.org', 'PO-10002', 289.00, 14.90, 303.90, 'PROCESSING', 'INVOICE', DATE('2025-10-11')
                    UNION ALL
                    SELECT 'elena.wagner@example.org', 'PO-10003', 149.50, 0.00, 149.50, 'SHIPPED', 'PAYPAL', DATE('2025-10-12')
                ) seed
                JOIN demo.customers c ON c.email = seed.customer_email
                """
            );
        }
    }

    private void seedOrderItems(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        Integer orderItemCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo.order_items", Integer.class);
        if (orderItemCount != null && orderItemCount == 0) {
            jdbcTemplate.update(
                """
                INSERT INTO demo.order_items (order_id, product_id, quantity, unit_price, line_total)
                SELECT po.id, p.id, seed.quantity, seed.unit_price, seed.line_total
                FROM (
                    SELECT 'PO-10001' AS order_number, 'LAP-14-PRO' AS sku, 1 AS quantity, 1299.00 AS unit_price, 1299.00 AS line_total
                    UNION ALL
                    SELECT 'PO-10001', 'MON-27-4K', 1, 349.90, 349.90
                    UNION ALL
                    SELECT 'PO-10002', 'CHR-ERG-01', 1, 289.00, 289.00
                    UNION ALL
                    SELECT 'PO-10003', 'DOC-USB-C', 1, 149.50, 149.50
                ) seed
                JOIN demo.orders po ON po.order_number = seed.order_number
                JOIN demo.products p ON p.sku = seed.sku
                """
            );
        }
    }
}
