-- Sample source schema that can be used for synthetic generation and masking demos.
-- Load it into the same MySQL instance:
--   mysql -u tdms_user -p tdms < src/main/resources/sql/sample_source_schema.sql

CREATE TABLE IF NOT EXISTS customer (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    first_name VARCHAR(80) NOT NULL,
    last_name VARCHAR(80) NOT NULL,
    email VARCHAR(150) NOT NULL,
    age INT NULL,
    city VARCHAR(120) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS purchase_order (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    order_number VARCHAR(40) NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    order_date DATE NOT NULL,
    CONSTRAINT fk_purchase_order_customer FOREIGN KEY (customer_id) REFERENCES customer (id)
);

INSERT INTO customer (first_name, last_name, email, age, city)
VALUES
    ('Nina', 'Huber', 'nina.huber@example.org', 29, 'Vienna'),
    ('Lukas', 'Mayer', 'lukas.mayer@example.org', 42, 'Linz'),
    ('Elena', 'Wagner', 'elena.wagner@example.org', 35, 'Graz');

INSERT INTO purchase_order (customer_id, order_number, total_amount, status, order_date)
VALUES
    (1, 'PO-10001', 129.90, 'PAID', '2025-10-10'),
    (2, 'PO-10002', 59.50, 'OPEN', '2025-10-11'),
    (3, 'PO-10003', 240.00, 'PAID', '2025-10-12');
