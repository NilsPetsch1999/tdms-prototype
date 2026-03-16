package fhcampus.nilspetsch.tdms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

@Component
public class DemoSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public DemoSchemaInitializer(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!isMySql()) {
            log.info("Skipping demo schema init because database is not MySQL.");
            return;
        }

        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS demo");

        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS demo.customer (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                first_name VARCHAR(80) NOT NULL,
                last_name VARCHAR(80) NOT NULL,
                email VARCHAR(150) NOT NULL,
                age INT NULL,
                city VARCHAR(120) NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        );

        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS demo.purchase_order (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                customer_id BIGINT NOT NULL,
                order_number VARCHAR(40) NOT NULL,
                total_amount DECIMAL(12,2) NOT NULL,
                status VARCHAR(20) NOT NULL,
                order_date DATE NOT NULL,
                CONSTRAINT fk_purchase_order_customer FOREIGN KEY (customer_id) REFERENCES demo.customer (id)
            )
            """
        );

        Integer customerCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo.customer", Integer.class);
        if (customerCount != null && customerCount == 0) {
            jdbcTemplate.update(
                """
                INSERT INTO demo.customer (first_name, last_name, email, age, city)
                VALUES
                    ('Nina', 'Huber', 'nina.huber@example.org', 29, 'Vienna'),
                    ('Lukas', 'Mayer', 'lukas.mayer@example.org', 42, 'Linz'),
                    ('Elena', 'Wagner', 'elena.wagner@example.org', 35, 'Graz')
                """
            );
        }

        Integer orderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo.purchase_order", Integer.class);
        if (orderCount != null && orderCount == 0) {
            jdbcTemplate.update(
                """
                INSERT INTO demo.purchase_order (customer_id, order_number, total_amount, status, order_date)
                VALUES
                    (1, 'PO-10001', 129.90, 'PAID', '2025-10-10'),
                    (2, 'PO-10002', 59.50, 'OPEN', '2025-10-11'),
                    (3, 'PO-10003', 240.00, 'PAID', '2025-10-12')
                """
            );
        }

        log.info("Demo schema initialization finished.");
    }

    private boolean isMySql() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            return metaData.getDatabaseProductName() != null
                && metaData.getDatabaseProductName().toLowerCase().contains("mysql");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to detect database type.", e);
        }
    }
}
